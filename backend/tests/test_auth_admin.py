from datetime import date

import pytest
from app.admin import api as admin_api
from app.admin import crud
from app.admin.resources import RESOURCES_BY_NAME, metadata
from app.domain.transport.exact_transit import trip_operates_on_date
from app.repositories import users as user_repository
from app.services import auth, holidays, school_holidays
from app.services.transport_graph import GraphTrip
from psycopg import sql


class FakeUsers:
    def __init__(self):
        self.users = {}
        self.tokens = {}
        self.next_id = 1

    def install(self, monkeypatch):
        monkeypatch.setattr(user_repository, "insert_user", self.insert_user)
        monkeypatch.setattr(user_repository, "find_user_with_password", self.find_user_with_password)
        monkeypatch.setattr(user_repository, "record_login", lambda user_id: None)
        monkeypatch.setattr(user_repository, "insert_token", self.insert_token)
        monkeypatch.setattr(user_repository, "find_user_by_token_hash", self.find_user_by_token_hash)
        monkeypatch.setattr(user_repository, "delete_token", lambda token_hash: self.tokens.pop(token_hash, None))
        monkeypatch.setattr(user_repository, "delete_user", self.delete_user)
        return self

    def insert_user(self, email, password_hash, display_name, is_admin=False):
        if any(user["email"] == email for user in self.users.values()):
            return None
        user = {
            "id": self.next_id,
            "email": email,
            "password_hash": password_hash,
            "display_name": display_name,
            "is_admin": is_admin,
            "is_active": True,
            "created_at": None,
            "last_login_at": None,
        }
        self.users[self.next_id] = user
        self.next_id += 1
        return dict(user)

    def find_user_with_password(self, email):
        return next((dict(user) for user in self.users.values() if user["email"] == email), None)

    def insert_token(self, user_id, token_hash, expires_at):
        self.tokens[token_hash] = user_id

    def find_user_by_token_hash(self, token_hash):
        user = self.users.get(self.tokens.get(token_hash))
        return dict(user) if user and user["is_active"] else None

    def delete_user(self, user_id):
        self.users.pop(user_id, None)
        self.tokens = {key: value for key, value in self.tokens.items() if value != user_id}


@pytest.fixture
def fake_users(monkeypatch):
    return FakeUsers().install(monkeypatch)


def test_password_hash_round_trip():
    stored = auth.hash_password("tajne-heslo")

    assert stored.startswith("scrypt$")
    assert auth.verify_password("tajne-heslo", stored)
    assert not auth.verify_password("ine-heslo", stored)
    assert not auth.verify_password("tajne-heslo", "nonsense")


def test_easter_based_holidays():
    assert holidays.easter_sunday(2026) == date(2026, 4, 5)
    names = dict(holidays.slovak_public_holidays(2026))
    assert names[date(2026, 4, 3)] == "Veľký piatok"
    assert names[date(2026, 4, 6)] == "Veľkonočný pondelok"
    assert date(2026, 9, 1) not in names


def test_rest_days_follow_current_law():
    days_2026 = {day for day, _ in holidays.slovak_public_holidays(2026)}
    days_2027 = {day for day, _ in holidays.slovak_public_holidays(2027)}

    assert len(days_2026) == 11
    assert not {date(2026, 5, 8), date(2026, 9, 15), date(2026, 11, 17), date(2026, 10, 28)} & days_2026
    assert len(days_2027) == 13
    assert {date(2027, 3, 26), date(2027, 3, 29), date(2027, 5, 8), date(2027, 9, 15)} <= days_2027
    assert not {date(2027, 9, 1), date(2027, 11, 17)} & days_2027


class FakeHolidayCursor:
    def __init__(self, present_years):
        self.present_years = present_years
        self.inserted = []
        self.rows = []
        self.rowcount = 0

    def execute(self, query, params):
        if query.startswith("SELECT"):
            self.rows = [{"year": year} for year in params[0] if year in self.present_years]
        else:
            self.inserted.append(params)
            self.rowcount = 1

    def fetchall(self):
        return self.rows


def test_only_missing_years_are_seeded():
    cursor = FakeHolidayCursor({2026})

    assert holidays.seed_missing_years(cursor, [2026, 2027]) == 13
    assert {params[0].year for params in cursor.inserted} == {2027}


def test_holiday_uses_weekend_timetable(monkeypatch):
    new_year = date(2025, 1, 1)
    monkeypatch.setattr(holidays, "holiday_dates", lambda: frozenset({new_year}))
    monkeypatch.setattr(school_holidays, "school_holiday_periods", lambda: ())
    workday_trip = GraphTrip(1, 1, "1", "test", "workdays", None, None, [])
    weekend_trip = GraphTrip(2, 1, "1", "test", "weekends_holidays", None, None, [])

    assert new_year.weekday() == 2
    assert not trip_operates_on_date(workday_trip, new_year)
    assert trip_operates_on_date(weekend_trip, new_year)
    assert trip_operates_on_date(workday_trip, date(2025, 1, 2))


def test_register_login_and_account_deletion(client, fake_users):
    registered = client.post("/auth/register", json={"email": " Turista@Example.com ", "password": "dlhe-heslo"})
    duplicate = client.post("/auth/register", json={"email": "turista@example.com", "password": "ine-heslo1"})
    wrong = client.post("/auth/login", json={"email": "turista@example.com", "password": "zle-heslo"})
    logged = client.post("/auth/login", json={"email": "TURISTA@example.com", "password": "dlhe-heslo"})
    headers = {"Authorization": f"Bearer {logged.json()['token']}"}

    assert registered.status_code == 201
    assert registered.json()["user"]["email"] == "turista@example.com"
    assert duplicate.status_code == 409
    assert wrong.status_code == 401
    assert client.get("/auth/me", headers=headers).json()["email"] == "turista@example.com"
    assert client.post("/auth/logout", headers=headers).status_code == 200
    assert client.get("/auth/me", headers=headers).status_code == 401
    other = {"Authorization": f"Bearer {registered.json()['token']}"}
    assert client.delete("/auth/me", headers=other).status_code == 200
    assert (
        client.post("/auth/login", json={"email": "turista@example.com", "password": "dlhe-heslo"}).status_code == 401
    )


def test_short_password_is_rejected(client, fake_users):
    response = client.post("/auth/register", json={"email": "a@b.sk", "password": "kratke"})

    assert response.status_code == 422


def admin_client(client, fake_users, is_admin=True):
    fake_users.insert_user("spravca@example.com", auth.hash_password("spravca-heslo"), "Správca", is_admin=is_admin)
    return client.post("/admin/api/login", json={"email": "spravca@example.com", "password": "spravca-heslo"})


def test_admin_requires_login_and_admin_rights(client, fake_users):
    assert client.get("/admin/api/meta").status_code == 401
    response = admin_client(client, fake_users, is_admin=False)

    assert response.status_code == 403


def test_admin_session_cookie_and_csrf_header(client, fake_users):
    login = admin_client(client, fake_users)

    assert login.status_code == 200
    assert "httponly" in login.headers["set-cookie"].lower()
    assert "samesite=strict" in login.headers["set-cookie"].lower()
    meta = client.get("/admin/api/meta")
    assert meta.status_code == 200
    assert {resource["name"] for resource in meta.json()["resources"]} >= {
        "pois",
        "users",
        "holidays",
        "route_sessions",
    }
    assert client.post("/admin/api/opening-hours/check", json={"value": "Mo-Fr 09:00-17:00"}).status_code == 403
    checked = client.post(
        "/admin/api/opening-hours/check", json={"value": "Mo-Fr 09:00-17:00"}, headers={"X-Admin-Request": "1"}
    )
    invalid = client.post(
        "/admin/api/opening-hours/check", json={"value": "kedykoľvek"}, headers={"X-Admin-Request": "1"}
    )
    assert checked.json()["valid"] is True
    assert len(checked.json()["week"]) == 7
    assert invalid.json()["valid"] is False


def test_admin_page_is_served(client):
    response = client.get("/admin")

    assert response.status_code == 200
    assert "admin.js" in response.text
    assert client.get("/admin/static/admin.js").status_code == 200


def test_opening_hours_override_validation():
    assert crud.validate_opening_hours("24x7") == "24/7"
    assert crud.validate_opening_hours("Mo-Fr  09:00-17:00") == "Mo-Fr 09:00-17:00"
    with pytest.raises(crud.AdminError):
        crud.validate_opening_hours("otvorené podľa nálady")


def test_admin_rules_for_pois_and_own_account():
    admin = {"id": 7, "email": "spravca@example.com"}
    pois = RESOURCES_BY_NAME["pois"]
    users = RESOURCES_BY_NAME["users"]

    created = admin_api.prepare_values(
        pois, {"opening_hours_override": "Tu-Su 10:00-18:00", "name": "Nový bod"}, {}, admin=admin, existing=None
    )
    assert created["opening_hours_override_by"] == "spravca@example.com"
    assert created["osm_type"] == "manual"
    with pytest.raises(crud.AdminError):
        admin_api.prepare_values(pois, {"base_score": 1.5}, {}, admin=admin, existing={"opening_hours_override": None})
    with pytest.raises(crud.AdminError):
        admin_api.prepare_values(users, {"is_admin": False}, {}, admin=admin, existing={"id": 7})
    with pytest.raises(crud.AdminError):
        admin_api.prepare_values(users, {"email": "x@y.sk"}, {}, admin=admin, existing=None)


def test_create_leaves_empty_optional_fields_to_database_defaults():
    routes = RESOURCES_BY_NAME["curated_routes"]
    data = {
        "title": "Okruh hradom",
        "slug": "okruh-hradom",
        "city_id": "1",
        "start_lat": "48,3157",
        "start_lon": "18.0870",
        "pace": "normal",
        "transport_mode": "walk",
        "theme": "",
    }

    created = crud.collect_values(routes, data, creating=True)
    assert "theme" not in created
    assert created["city_id"] == 1
    assert created["start_lat"] == 48.3157
    with pytest.raises(crud.AdminError):
        crud.collect_values(routes, {**data, "pace": ""}, creating=True)
    with pytest.raises(crud.AdminError):
        crud.collect_values(routes, {**data, "transport_mode": "lietadlo"}, creating=True)
    assert crud.collect_values(routes, {"theme": ""}, creating=False) == {"theme": None}
    columns = {
        column["name"]: column for column in next(r for r in metadata() if r["name"] == "curated_routes")["columns"]
    }
    assert columns["pace"]["default"] == "normal"
    assert columns["transport_mode"]["choice_labels"]["walk_or_mhd"] == "Pešo alebo MHD"


def test_admin_list_query_uses_only_whitelisted_identifiers():
    pois = RESOURCES_BY_NAME["pois"]
    where, params = crud.build_where(
        pois, "hrad", {"city_id": "1", "is_active": "true", "opening_hours_override": "set", "name; DROP": "x"}
    )
    text = where.as_string(None)

    assert "DROP" not in text
    assert '"opening_hours_override" IS NOT NULL' in text
    assert params == ["%hrad%", "%hrad%", "%hrad%", 1, True]
    with pytest.raises(crud.AdminError):
        crud.get_resource("pg_shadow")
    assert isinstance(crud.key_condition(RESOURCES_BY_NAME["category_feedback_stats"]), sql.Composed)
