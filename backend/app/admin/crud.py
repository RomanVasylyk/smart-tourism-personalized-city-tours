from __future__ import annotations

import uuid
from datetime import date, datetime
from typing import Any

from app.admin.resources import CHOICE_LABELS, RESOURCES_BY_NAME, Column, Resource
from app.db.database import get_connection
from app.services.route_planning.opening_hours import ALWAYS_OPEN_VALUES, parse_opening_rules
from fastapi import HTTPException
from psycopg import errors, sql

MAX_PAGE_SIZE = 200
OPTIONS_LIMIT = 50


class AdminError(HTTPException):
    def __init__(self, status_code: int, detail: str):
        super().__init__(status_code=status_code, detail=detail)


def get_resource(name: str) -> Resource:
    resource = RESOURCES_BY_NAME.get(name)
    if resource is None:
        raise AdminError(404, "Neznámy zdroj údajov.")
    return resource


def coerce(column: Column, value: Any) -> Any:
    if value is None or (isinstance(value, str) and value.strip() == "" and column.kind != "bool"):
        if column.required:
            raise AdminError(422, f"Pole „{column.label}“ je povinné.")
        return None
    try:
        if column.kind in ("int", "ref"):
            return int(value)
        if column.kind == "float":
            return float(str(value).replace(",", "."))
        if column.kind == "bool":
            return value if isinstance(value, bool) else str(value).lower() in ("1", "true", "on", "áno", "ano")
        if column.kind == "date":
            return value if isinstance(value, date) else date.fromisoformat(str(value))
        if column.kind == "datetime":
            return value if isinstance(value, datetime) else datetime.fromisoformat(str(value))
        if column.kind == "uuid":
            return uuid.UUID(str(value))
    except (TypeError, ValueError) as exc:
        raise AdminError(422, f"Pole „{column.label}“ má nesprávny formát.") from exc
    text = str(value).strip()
    if column.kind == "choice" and column.choices and text not in column.choices:
        raise AdminError(422, f"Pole „{column.label}“ má nepovolenú hodnotu.")
    return text


def validate_opening_hours(value: str | None) -> str | None:
    if value is None:
        return None
    normalized = " ".join(value.split())
    if normalized.lower() in ALWAYS_OPEN_VALUES:
        return "24/7"
    if not parse_opening_rules(normalized):
        raise AdminError(
            422, "Otváracie hodiny sa nepodarilo rozpoznať. Použite formát OSM, napríklad Mo-Fr 09:00-17:00."
        )
    return normalized


def parse_key(resource: Resource, key: str) -> list[Any]:
    parts = key.split("~") if len(resource.pk) > 1 else [key]
    if len(parts) != len(resource.pk):
        raise AdminError(404, "Záznam neexistuje.")
    values = []
    for name, part in zip(resource.pk, parts, strict=True):
        column = resource.column(name)
        values.append(coerce(Column(name, column.label, column.kind, required=True), part))
    return values


def key_condition(resource: Resource) -> sql.Composed:
    return sql.SQL(" AND ").join(sql.SQL("{} = %s").format(sql.Identifier(name)) for name in resource.pk)


def list_columns(resource: Resource) -> list[Column]:
    return [column for column in resource.stored_columns if column.in_list or column.name in resource.pk]


def build_where(resource: Resource, search: str | None, filters: dict[str, str]) -> tuple[sql.Composable, list[Any]]:
    conditions: list[sql.Composable] = []
    params: list[Any] = []
    if search and resource.search:
        conditions.append(
            sql.SQL("({})").format(
                sql.SQL(" OR ").join(
                    sql.SQL("CAST({} AS TEXT) ILIKE %s").format(sql.Identifier(name)) for name in resource.search
                )
            )
        )
        params.extend([f"%{search.strip()}%"] * len(resource.search))
    for name, raw in filters.items():
        column = resource.column(name)
        if column is None or name not in resource.filters or raw in ("", None):
            continue
        if column.kind in ("text", "opening_hours", "longtext") and raw in ("set", "empty"):
            template = "{} IS NOT NULL" if raw == "set" else "{} IS NULL"
            conditions.append(sql.SQL(template).format(sql.Identifier(name)))
            continue
        conditions.append(sql.SQL("{} = %s").format(sql.Identifier(name)))
        params.append(coerce(Column(name, column.label, column.kind, required=True, choices=column.choices), raw))
    if not conditions:
        return sql.SQL(""), params
    return sql.SQL(" WHERE ") + sql.SQL(" AND ").join(conditions), params


def label_text(resource: Resource, row: dict) -> str:
    parts = []
    for name in resource.label_fields:
        value = row.get(name)
        if value is None or value == "":
            continue
        column = resource.column(name)
        parts.append(CHOICE_LABELS.get(value, value) if column and column.kind == "choice" else str(value))
    return " · ".join(parts)


def label_query(resource: Resource) -> sql.Composed:
    return sql.SQL("SELECT {pk} AS value, {fields} FROM {table}").format(
        pk=sql.Identifier(resource.pk[0]),
        fields=sql.SQL(", ").join(sql.Identifier(name) for name in resource.label_fields),
        table=sql.Identifier(resource.table),
    )


def ref_labels(cur, resource: Resource, rows: list[dict]) -> dict[str, dict[str, str]]:
    labels: dict[str, dict[str, str]] = {}
    for column in resource.columns:
        if column.kind != "ref" or not column.ref:
            continue
        target = RESOURCES_BY_NAME[column.ref]
        ids = sorted({row[column.name] for row in rows if row.get(column.name) is not None})
        if not ids or not target.label_fields:
            continue
        cur.execute(label_query(target) + sql.SQL(" WHERE {} = ANY(%s)").format(sql.Identifier(target.pk[0])), (ids,))
        labels[column.name] = {str(row["value"]): label_text(target, row) for row in cur.fetchall()}
    return labels


def list_rows(
    resource: Resource,
    *,
    search: str | None,
    filters: dict[str, str],
    sort: str | None,
    descending: bool | None,
    page: int,
    page_size: int,
) -> dict:
    columns = list_columns(resource)
    sortable = {column.name for column in resource.stored_columns if column.kind != "json"}
    sort_column = sort if sort in sortable else resource.order
    is_desc = resource.order_desc if descending is None or sort not in sortable else descending
    page_size = max(1, min(page_size, MAX_PAGE_SIZE))
    page = max(1, page)
    where, params = build_where(resource, search, filters)
    select = sql.SQL(", ").join(sql.Identifier(column.name) for column in columns)
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                sql.SQL("SELECT COUNT(*) AS total FROM {}").format(sql.Identifier(resource.table)) + where, params
            )
            total = cur.fetchone()["total"]
            cur.execute(
                sql.SQL("SELECT {select} FROM {table}").format(select=select, table=sql.Identifier(resource.table))
                + where
                + sql.SQL(" ORDER BY {} {} NULLS LAST LIMIT %s OFFSET %s").format(
                    sql.Identifier(sort_column), sql.SQL("DESC" if is_desc else "ASC")
                ),
                [*params, page_size, (page - 1) * page_size],
            )
            rows = cur.fetchall()
            labels = ref_labels(cur, resource, rows)
    return {"rows": rows, "total": total, "page": page, "page_size": page_size, "labels": labels}


def get_row(resource: Resource, key: str) -> dict:
    values = parse_key(resource, key)
    select = sql.SQL(", ").join(sql.Identifier(column.name) for column in resource.stored_columns)
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                sql.SQL("SELECT {} FROM {} WHERE ").format(select, sql.Identifier(resource.table))
                + key_condition(resource),
                values,
            )
            row = cur.fetchone()
            if row is None:
                raise AdminError(404, "Záznam neexistuje.")
            labels = ref_labels(cur, resource, [row])
    return {"row": row, "labels": labels}


def collect_values(resource: Resource, data: dict[str, Any], *, creating: bool) -> dict[str, Any]:
    values: dict[str, Any] = {}
    for column in resource.stored_columns:
        if not column.editable:
            continue
        if column.name in data:
            value = coerce(column, data[column.name])
            if value is not None or not creating:
                values[column.name] = value
        elif creating and column.required:
            raise AdminError(422, f"Pole „{column.label}“ je povinné.")
    return values


def run_write(statement: sql.Composable, params: list[Any], resource: Resource) -> dict | None:
    with get_connection() as conn:
        with conn.cursor() as cur:
            try:
                cur.execute(statement, params)
            except errors.ForeignKeyViolation as exc:
                conn.rollback()
                raise AdminError(409, resource.delete_hint or "Záznam súvisí s inými údajmi.") from exc
            except errors.UniqueViolation as exc:
                conn.rollback()
                raise AdminError(409, "Záznam s rovnakými údajmi už existuje.") from exc
            except errors.CheckViolation as exc:
                conn.rollback()
                raise AdminError(422, "Hodnoty nespĺňajú pravidlá databázy.") from exc
            except errors.NotNullViolation as exc:
                conn.rollback()
                column = resource.column(exc.diag.column_name or "")
                label = column.label if column else exc.diag.column_name
                raise AdminError(422, f"Pole „{label}“ je povinné.") from exc
            row = cur.fetchone() if cur.description else None
            conn.commit()
            return row


def insert_row(resource: Resource, values: dict[str, Any]) -> dict:
    names = list(values)
    statement = sql.SQL("INSERT INTO {table} ({columns}) VALUES ({placeholders}) RETURNING {pk}").format(
        table=sql.Identifier(resource.table),
        columns=sql.SQL(", ").join(sql.Identifier(name) for name in names),
        placeholders=sql.SQL(", ").join(sql.Placeholder() for _ in names),
        pk=sql.SQL(", ").join(sql.Identifier(name) for name in resource.pk),
    )
    return run_write(statement, [values[name] for name in names], resource)


def update_row(resource: Resource, key: str, values: dict[str, Any]) -> None:
    key_values = parse_key(resource, key)
    if not values:
        return
    statement = (
        sql.SQL("UPDATE {table} SET {assignments} WHERE ").format(
            table=sql.Identifier(resource.table),
            assignments=sql.SQL(", ").join(sql.SQL("{} = %s").format(sql.Identifier(name)) for name in values),
        )
        + key_condition(resource)
        + sql.SQL(" RETURNING 1 AS updated")
    )
    if run_write(statement, [*values.values(), *key_values], resource) is None:
        raise AdminError(404, "Záznam neexistuje.")


def delete_row(resource: Resource, key: str) -> None:
    key_values = parse_key(resource, key)
    statement = (
        sql.SQL("DELETE FROM {} WHERE ").format(sql.Identifier(resource.table))
        + key_condition(resource)
        + sql.SQL(" RETURNING 1 AS deleted")
    )
    if run_write(statement, key_values, resource) is None:
        raise AdminError(404, "Záznam neexistuje.")


def options(resource: Resource, search: str | None, ids: list[str]) -> list[dict]:
    if not resource.label_fields:
        return []
    query = label_query(resource)
    fields = [sql.Identifier(name) for name in resource.label_fields]
    with get_connection() as conn:
        with conn.cursor() as cur:
            if ids:
                key_column = resource.column(resource.pk[0])
                values = [
                    coerce(Column(key_column.name, key_column.label, key_column.kind, required=True), item)
                    for item in ids
                ]
                cur.execute(query + sql.SQL(" WHERE {} = ANY(%s)").format(sql.Identifier(resource.pk[0])), (values,))
            else:
                haystack = sql.SQL("CONCAT_WS(' ', {})").format(
                    sql.SQL(", ").join(sql.SQL("CAST({} AS TEXT)").format(field) for field in fields)
                )
                tokens = (search or "").split() or [""]
                condition = sql.SQL(" AND ").join(sql.SQL("{} ILIKE %s").format(haystack) for _ in tokens)
                cur.execute(
                    query + sql.SQL(" WHERE {} ORDER BY {} LIMIT %s").format(condition, sql.SQL(", ").join(fields)),
                    [*(f"%{token}%" for token in tokens), OPTIONS_LIMIT],
                )
            return [{"value": row["value"], "label": label_text(resource, row)} for row in cur.fetchall()]
