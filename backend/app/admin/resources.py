from __future__ import annotations

from dataclasses import dataclass

from app.domain.transport.service_days import SCHOOL_HOLIDAY_KINDS, SERVICE_BUCKETS

CATEGORIES = (
    "attraction",
    "museum",
    "gallery",
    "viewpoint",
    "monument",
    "historical_site",
    "park",
    "religious_site",
)
PACES = ("slow", "normal", "fast")
TRANSPORT_MODES = ("walk", "walk_or_mhd")
SESSION_STATUSES = ("not_started", "in_progress", "paused", "completed", "cancelled")
CHOICE_LABELS = {
    "attraction": "Atrakcia",
    "museum": "Múzeum",
    "gallery": "Galéria",
    "viewpoint": "Vyhliadka",
    "monument": "Pamätník",
    "historical_site": "Historické miesto",
    "park": "Park",
    "religious_site": "Sakrálna stavba",
    "slow": "Pomalé",
    "normal": "Bežné",
    "fast": "Rýchle",
    "walk": "Pešo",
    "walk_or_mhd": "Pešo alebo MHD",
    "all_days": "Denne",
    "workdays": "Pracovné dni",
    "school_days": "Pracovné dni počas školského roka",
    "school_holidays": "Pracovné dni počas školských prázdnin",
    "summer_holidays": "Pracovné dni počas letných prázdnin",
    "workdays_except_summer": "Pracovné dni okrem letných prázdnin",
    "workdays_except_winter": "Pracovné dni okrem zimných prázdnin",
    "workdays_except_summer_winter": "Pracovné dni okrem letných a zimných prázdnin",
    "weekends_holidays": "Voľné dni (víkendy a sviatky)",
    "weekends_holidays_apr_sep": "Voľné dni od apríla do septembra",
    "weekends_holidays_oct_mar": "Voľné dni od októbra do marca",
    "saturdays": "Soboty",
    "sundays_holidays": "Nedele a sviatky",
    "nights_before_rest_day": "Noc pred voľným dňom",
    "nights_before_workday": "Noc pred pracovným dňom",
    "autumn": "Jesenné",
    "christmas": "Vianočné",
    "half_year": "Polročné",
    "spring": "Jarné",
    "easter": "Veľkonočné",
    "summer": "Letné",
    "transport": "Cestovné poriadky MHD",
    "pois": "Body záujmu",
    "not_started": "Nezačatá",
    "in_progress": "Prebieha",
    "paused": "Pozastavená",
    "completed": "Dokončená",
    "cancelled": "Zrušená",
}


@dataclass(frozen=True)
class Column:
    name: str
    label: str
    kind: str = "text"
    editable: bool = True
    required: bool = False
    in_list: bool = True
    ref: str | None = None
    choices: tuple[str, ...] = ()
    help: str | None = None
    default: object = None
    empty_text: str | None = None


@dataclass(frozen=True)
class Resource:
    name: str
    table: str
    label: str
    group: str
    columns: tuple[Column, ...]
    pk: tuple[str, ...] = ("id",)
    title: str | None = None
    label_columns: tuple[str, ...] = ()
    search: tuple[str, ...] = ()
    filters: tuple[str, ...] = ()
    order: str = "id"
    order_desc: bool = False
    can_create: bool = True
    can_edit: bool = True
    can_delete: bool = True
    delete_hint: str | None = None

    def column(self, name: str) -> Column | None:
        return next((column for column in self.columns if column.name == name), None)

    @property
    def label_fields(self) -> tuple[str, ...]:
        if self.label_columns:
            return self.label_columns
        return (self.title,) if self.title else ()

    @property
    def stored_columns(self) -> tuple[Column, ...]:
        return tuple(column for column in self.columns if column.kind != "password")


def ro(name: str, label: str, kind: str = "text", **kwargs) -> Column:
    return Column(name, label, kind, editable=False, **kwargs)


FEEDBACK_STAT_COLUMNS = (
    ro("session_count", "Trás", "int"),
    ro("feedback_count", "Hodnotení", "int"),
    ro("average_rating", "Priemerné hodnotenie", "float"),
    ro("completion_rate", "Miera dokončenia", "float"),
    ro("skip_rate", "Miera preskočenia", "float"),
    ro("too_much_walking_rate", "Priveľa chôdze", "float", in_list=False),
    ro("interesting_pois_rate", "Zaujímavé body", "float", in_list=False),
    ro("convenient_rate", "Pohodlná doprava", "float", in_list=False),
    ro("updated_at", "Prepočítané", "datetime"),
)

RESOURCES: tuple[Resource, ...] = (
    Resource(
        name="data_loads",
        table="data_loads",
        label="História importov",
        group="Dátová pipeline",
        filters=("dataset", "city_slug"),
        order="loaded_at",
        order_desc=True,
        can_create=False,
        can_edit=False,
        can_delete=False,
        columns=(
            ro("id", "ID", "int"),
            ro("dataset", "Dáta", "choice", choices=("transport", "pois")),
            ro("city_slug", "Mesto"),
            ro("file_name", "Súbor"),
            ro("loaded_by", "Načítal", empty_text="príkazový riadok"),
            ro("loaded_at", "Čas načítania", "datetime"),
            ro("sha256", "Kontrolný súčet", in_list=False),
        ),
    ),
    Resource(
        name="cities",
        table="cities",
        label="Mestá",
        group="Obsah",
        title="name",
        search=("name", "country"),
        order="name",
        columns=(
            ro("id", "ID", "int"),
            Column("name", "Názov", required=True),
            Column("country", "Krajina", required=True, default="Slovakia"),
            Column("center_lat", "Stred – zemepisná šírka", "float"),
            Column("center_lon", "Stred – zemepisná dĺžka", "float"),
        ),
        delete_hint="Mesto má naviazané body záujmu alebo trasy. Najprv ich odstráňte.",
    ),
    Resource(
        name="pois",
        table="pois",
        label="Body záujmu",
        group="Obsah",
        title="name",
        search=("name", "address", "category"),
        filters=("city_id", "category", "is_active", "opening_hours_override"),
        order="name",
        columns=(
            ro("id", "ID", "int"),
            Column("name", "Názov", required=True),
            Column("city_id", "Mesto", "ref", required=True, ref="cities"),
            Column("category", "Kategória", "choice", required=True, choices=CATEGORIES),
            Column("subcategory", "Podkategória", in_list=False),
            Column("lat", "Zemepisná šírka", "float", required=True, in_list=False),
            Column("lon", "Zemepisná dĺžka", "float", required=True, in_list=False),
            Column("address", "Adresa", in_list=False),
            Column(
                "opening_hours_override",
                "Otváracie hodiny (správca)",
                "opening_hours",
                help=(
                    "Ak pole vyplníte, plánovač použije tieto hodiny namiesto údajov z OSM. Ak je prázdne, "
                    "použijú sa hodiny z OSM, a ak chýbajú aj tie, bod sa považuje za prístupný nonstop."
                ),
            ),
            ro("opening_hours_raw", "Otváracie hodiny (OSM)", in_list=False),
            ro("opening_hours_source", "Zdroj hodín v OSM", in_list=False),
            ro("opening_hours_override_by", "Hodiny upravil", in_list=False),
            ro("opening_hours_override_at", "Hodiny upravené", "datetime", in_list=False),
            Column("visit_duration_min", "Dĺžka návštevy [min]", "int", default=20),
            Column("base_score", "Základné skóre (0 – 1)", "float", default=0.5),
            Column("short_description", "Krátky opis", "longtext", in_list=False),
            Column("website", "Webová stránka", in_list=False),
            Column("image_url", "Obrázok (URL)", in_list=False),
            Column("wikidata_id", "Wikidata", in_list=False),
            Column("wikipedia_title", "Článok vo Wikipédii", in_list=False),
            Column("wikipedia_url", "Odkaz na Wikipédiu", in_list=False),
            ro("osm_type", "Typ v OSM", in_list=False),
            ro("osm_id", "ID v OSM", in_list=False),
            ro("source", "Zdroj", in_list=False),
            Column(
                "admin_locked",
                "Chrániť pred importom",
                "bool",
                in_list=False,
                help=(
                    "Zapne sa samo, keď správca zmení údaje prevzaté z pipeline. Import ich potom neprepíše. "
                    "Ak chcete znova prevziať údaje z OpenStreetMap, vypnite ho."
                ),
            ),
            Column("is_active", "Aktívny", "bool", default=True),
        ),
        delete_hint="Bod sa používa v trasách návštevníkov. Namiesto vymazania ho deaktivujte.",
    ),
    Resource(
        name="curated_routes",
        table="curated_routes",
        label="Kurátorské okruhy",
        group="Obsah",
        title="title",
        search=("title", "slug", "theme"),
        filters=("city_id", "is_active"),
        order="title",
        columns=(
            ro("id", "ID", "int"),
            Column("title", "Názov", required=True),
            Column("slug", "Skratka (slug)", required=True),
            Column("city_id", "Mesto", "ref", required=True, ref="cities"),
            Column("theme", "Téma"),
            Column("summary", "Opis", "longtext", in_list=False),
            Column("start_lat", "Štart – zemepisná šírka", "float", required=True, in_list=False),
            Column("start_lon", "Štart – zemepisná dĺžka", "float", required=True, in_list=False),
            Column("recommended_duration_min", "Odporúčaná dĺžka [min]", "int"),
            Column("pace", "Tempo", "choice", required=True, choices=PACES, in_list=False, default="normal"),
            Column(
                "transport_mode",
                "Doprava",
                "choice",
                required=True,
                choices=TRANSPORT_MODES,
                in_list=False,
                default="walk",
            ),
            Column("return_to_start", "Návrat na štart", "bool", in_list=False, default=True),
            Column("is_active", "Aktívny", "bool", default=True),
            ro("updated_at", "Upravené", "datetime", in_list=False),
        ),
    ),
    Resource(
        name="curated_route_pois",
        table="curated_route_pois",
        label="Zastávky okruhov",
        group="Obsah",
        filters=("route_id",),
        order="route_id",
        columns=(
            ro("id", "ID", "int"),
            Column("route_id", "Okruh", "ref", required=True, ref="curated_routes"),
            Column("poi_id", "Bod záujmu", "ref", required=True, ref="pois"),
            Column("visit_order", "Poradie", "int", required=True),
            Column("visit_duration_min", "Dĺžka návštevy [min]", "int"),
            Column("note", "Poznámka", "longtext", in_list=False),
        ),
    ),
    Resource(
        name="holidays",
        table="holidays",
        label="Štátne sviatky",
        group="Kalendár",
        title="name",
        search=("name",),
        filters=("is_active",),
        order="holiday_date",
        order_desc=True,
        columns=(
            ro("id", "ID", "int"),
            Column("holiday_date", "Dátum", "date", required=True),
            Column("name", "Názov", required=True),
            Column(
                "is_active",
                "Platí",
                "bool",
                default=True,
                help=(
                    "Počas platného sviatku plánovač použije cestovný poriadok MHD pre víkendy a sviatky. "
                    "Štátne sviatky na aktuálny a nasledujúci rok sa doplnia automaticky, preto deň, ktorý nemá "
                    "platiť, nevymazávajte, ale zrušte mu tento príznak."
                ),
            ),
        ),
    ),
    Resource(
        name="school_holidays",
        table="school_holidays",
        label="Školské prázdniny",
        group="Kalendár",
        title="name",
        search=("name",),
        filters=("kind", "city_id", "is_active"),
        order="start_date",
        order_desc=True,
        columns=(
            ro("id", "ID", "int"),
            Column("name", "Názov", required=True),
            Column("kind", "Druh", "choice", required=True, choices=SCHOOL_HOLIDAY_KINDS),
            Column("start_date", "Prvý deň", "date", required=True),
            Column("end_date", "Posledný deň", "date", required=True),
            Column(
                "city_id",
                "Mesto",
                "ref",
                ref="cities",
                empty_text="všetky mestá",
                help="Prázdne pole znamená všetky mestá. Jarné prázdniny sa líšia podľa kraja, preto majú mesto.",
            ),
            Column(
                "is_active",
                "Platí",
                "bool",
                default=True,
                help=(
                    "V pracovný deň počas platných prázdnin plánovač použije cestovný poriadok MHD pre školské "
                    "prázdniny. Termíny na školský rok 2026/2027 a 2027/2028 sa doplnia podľa ministerstva školstva."
                ),
            ),
        ),
    ),
    Resource(
        name="transport_lines",
        table="transport_lines",
        label="Linky MHD",
        group="Doprava",
        title="name",
        label_columns=("name", "direction_name", "service_bucket"),
        search=("name", "provider_line_id", "direction_name"),
        filters=("city_id", "service_bucket", "is_active"),
        order="name",
        can_create=False,
        can_delete=False,
        columns=(
            ro("id", "ID", "int"),
            Column("name", "Linka", required=True),
            Column("direction_name", "Smer"),
            ro("city_id", "Mesto", "ref", ref="cities"),
            ro("provider", "Poskytovateľ", in_list=False),
            ro("provider_line_id", "ID u poskytovateľa", in_list=False),
            Column("service_bucket", "Dni premávky", "choice", choices=SERVICE_BUCKETS),
            Column("valid_from", "Platí od", "date", in_list=False),
            Column("valid_to", "Platí do", "date", in_list=False),
            ro("source_url", "Zdroj", in_list=False),
            Column("is_active", "Aktívna", "bool"),
        ),
    ),
    Resource(
        name="transport_stops",
        table="transport_stops",
        label="Zastávky MHD",
        group="Doprava",
        title="name",
        label_columns=("name", "platform_ref"),
        search=("name", "provider_stop_code"),
        filters=("city_id", "is_active"),
        order="name",
        can_create=False,
        can_delete=False,
        columns=(
            ro("id", "ID", "int"),
            Column("name", "Názov", required=True),
            ro("city_id", "Mesto", "ref", ref="cities"),
            Column("lat", "Zemepisná šírka", "float", required=True, in_list=False),
            Column("lon", "Zemepisná dĺžka", "float", required=True, in_list=False),
            Column("platform_ref", "Nástupište", in_list=False),
            ro("provider_stop_code", "Kód zastávky", in_list=False),
            ro("source", "Zdroj", in_list=False),
            Column("is_active", "Aktívna", "bool"),
        ),
    ),
    Resource(
        name="transport_trips",
        table="transport_trips",
        label="Spoje MHD",
        group="Doprava",
        search=("trip_code",),
        filters=("line_id", "service_bucket", "is_active"),
        order="id",
        can_create=False,
        can_delete=False,
        columns=(
            ro("id", "ID", "int"),
            ro("line_id", "Linka", "ref", ref="transport_lines"),
            ro("trip_code", "Kód spoja"),
            Column("service_bucket", "Dni premávky", "choice", choices=SERVICE_BUCKETS),
            Column("valid_from", "Platí od", "date"),
            Column("valid_to", "Platí do", "date"),
            Column("is_active", "Aktívny", "bool"),
        ),
    ),
    Resource(
        name="transport_trip_stop_times",
        table="transport_trip_stop_times",
        label="Časy spojov",
        group="Doprava",
        filters=("trip_id",),
        order="id",
        can_create=False,
        can_delete=False,
        columns=(
            ro("id", "ID", "int"),
            ro("trip_id", "Spoj", "int"),
            ro("stop_id", "Zastávka", "ref", ref="transport_stops"),
            ro("stop_sequence", "Poradie", "int"),
            Column("time_minutes", "Čas [min od polnoci]", "int", required=True),
        ),
    ),
    Resource(
        name="users",
        table="users",
        label="Používatelia",
        group="Používatelia a trasy",
        title="email",
        search=("email", "display_name"),
        filters=("is_admin", "is_active"),
        order="id",
        order_desc=True,
        columns=(
            ro("id", "ID", "int"),
            Column("email", "E-mail", required=True),
            Column("display_name", "Meno"),
            Column("is_admin", "Správca", "bool", default=False),
            Column("is_active", "Aktívny", "bool", default=True),
            Column(
                "password",
                "Nové heslo",
                "password",
                in_list=False,
                help=(
                    "Vyplňte iba pri vytváraní účtu alebo pri zmene hesla. Po zmene hesla sa používateľ "
                    "odhlási zo všetkých zariadení."
                ),
            ),
            ro("created_at", "Registrovaný", "datetime"),
            ro("last_login_at", "Posledné prihlásenie", "datetime"),
        ),
    ),
    Resource(
        name="user_tokens",
        table="user_tokens",
        label="Prihlásenia",
        group="Používatelia a trasy",
        filters=("user_id",),
        order="id",
        order_desc=True,
        can_create=False,
        can_edit=False,
        columns=(
            ro("id", "ID", "int"),
            ro("user_id", "Používateľ", "ref", ref="users"),
            ro("created_at", "Vytvorené", "datetime"),
            ro("last_used_at", "Naposledy použité", "datetime"),
            ro("expires_at", "Platí do", "datetime"),
        ),
    ),
    Resource(
        name="route_sessions",
        table="route_sessions",
        label="Trasy návštevníkov",
        group="Používatelia a trasy",
        filters=("city_id", "status", "user_id"),
        order="started_at",
        order_desc=True,
        can_create=False,
        columns=(
            ro("id", "ID", "uuid"),
            ro("city_id", "Mesto", "ref", ref="cities"),
            ro("user_id", "Používateľ", "ref", ref="users"),
            Column("status", "Stav", "choice", required=True, choices=SESSION_STATUSES),
            ro("started_at", "Začiatok", "datetime"),
            ro("finished_at", "Koniec", "datetime"),
            ro("available_minutes", "Rozpočet [min]", "int"),
            ro("used_minutes", "Využité [min]", "int"),
            ro("pace", "Tempo", "choice", choices=PACES, in_list=False),
            ro("total_walk_minutes", "Chôdza [min]", "int", in_list=False),
            ro("total_visit_minutes", "Návštevy [min]", "int", in_list=False),
            ro("device_id", "Zariadenie", in_list=False),
            ro("route_snapshot_json", "Snímka trasy", "json", in_list=False),
        ),
    ),
    Resource(
        name="route_session_pois",
        table="route_session_pois",
        label="Zastávky trás",
        group="Používatelia a trasy",
        filters=("session_id", "visited", "skipped"),
        order="id",
        order_desc=True,
        can_create=False,
        can_edit=False,
        columns=(
            ro("id", "ID", "int"),
            ro("session_id", "Trasa", "uuid"),
            ro("poi_id", "Bod záujmu", "ref", ref="pois"),
            ro("visit_order", "Poradie", "int"),
            ro("visited", "Navštívený", "bool"),
            ro("skipped", "Preskočený", "bool"),
            ro("visited_at", "Čas návštevy", "datetime"),
        ),
    ),
    Resource(
        name="route_feedback",
        table="route_feedback",
        label="Hodnotenia trás",
        group="Používatelia a trasy",
        filters=("rating",),
        order="created_at",
        order_desc=True,
        can_create=False,
        can_edit=False,
        columns=(
            ro("id", "ID", "int"),
            ro("session_id", "Trasa", "uuid"),
            ro("rating", "Hodnotenie", "int"),
            ro("pois_were_interesting", "Zaujímavé body", "bool"),
            ro("too_much_walking", "Priveľa chôdze", "bool"),
            ro("was_convenient", "Pohodlná doprava", "bool"),
            ro("comment", "Komentár", "longtext"),
            ro("created_at", "Vytvorené", "datetime"),
        ),
    ),
    Resource(
        name="poi_feedback_stats",
        table="poi_feedback_stats",
        label="Štatistiky bodov",
        group="Štatistiky spätnej väzby",
        pk=("poi_id",),
        order="feedback_count",
        order_desc=True,
        can_create=False,
        can_edit=False,
        can_delete=False,
        columns=(ro("poi_id", "Bod záujmu", "ref", ref="pois"), *FEEDBACK_STAT_COLUMNS),
    ),
    Resource(
        name="category_feedback_stats",
        table="category_feedback_stats",
        label="Štatistiky kategórií",
        group="Štatistiky spätnej väzby",
        pk=("city_id", "category"),
        filters=("city_id",),
        order="feedback_count",
        order_desc=True,
        can_create=False,
        can_edit=False,
        can_delete=False,
        columns=(
            ro("city_id", "Mesto", "ref", ref="cities"),
            ro("category", "Kategória", "choice", choices=CATEGORIES),
            *FEEDBACK_STAT_COLUMNS,
        ),
    ),
    Resource(
        name="city_feedback_stats",
        table="city_feedback_stats",
        label="Štatistiky miest",
        group="Štatistiky spätnej väzby",
        pk=("city_id",),
        order="feedback_count",
        order_desc=True,
        can_create=False,
        can_edit=False,
        can_delete=False,
        columns=(ro("city_id", "Mesto", "ref", ref="cities"), *FEEDBACK_STAT_COLUMNS),
    ),
    Resource(
        name="transport_mode_feedback_stats",
        table="transport_mode_feedback_stats",
        label="Štatistiky dopravy",
        group="Štatistiky spätnej väzby",
        pk=("city_id", "transport_mode"),
        order="feedback_count",
        order_desc=True,
        can_create=False,
        can_edit=False,
        can_delete=False,
        columns=(
            ro("city_id", "Mesto", "ref", ref="cities"),
            ro("transport_mode", "Doprava"),
            *FEEDBACK_STAT_COLUMNS,
        ),
    ),
)

RESOURCES_BY_NAME = {resource.name: resource for resource in RESOURCES}

SECTIONS: dict[str, dict[str, tuple[str, ...]]] = {
    "pois": {
        "Základné údaje": (
            "name",
            "city_id",
            "category",
            "subcategory",
            "visit_duration_min",
            "base_score",
            "is_active",
        ),
        "Poloha": ("lat", "lon", "address"),
        "Otváracie hodiny": (
            "opening_hours_override",
            "opening_hours_raw",
            "opening_hours_source",
            "opening_hours_override_by",
            "opening_hours_override_at",
        ),
        "Opis a odkazy": (
            "short_description",
            "website",
            "image_url",
            "wikidata_id",
            "wikipedia_title",
            "wikipedia_url",
        ),
        "Pôvod údajov": ("id", "osm_type", "osm_id", "source", "admin_locked"),
    },
    "curated_routes": {
        "Základné údaje": ("title", "slug", "city_id", "theme", "summary", "is_active"),
        "Štart a parametre": (
            "start_lat",
            "start_lon",
            "recommended_duration_min",
            "pace",
            "transport_mode",
            "return_to_start",
        ),
        "Záznam": ("id", "updated_at"),
    },
    "users": {
        "Účet": ("email", "display_name", "password"),
        "Oprávnenia": ("is_admin", "is_active"),
        "Záznam": ("id", "created_at", "last_login_at"),
    },
    "route_sessions": {
        "Priebeh trasy": (
            "status",
            "city_id",
            "user_id",
            "pace",
            "available_minutes",
            "used_minutes",
            "total_walk_minutes",
            "total_visit_minutes",
        ),
        "Zariadenie a čas": ("id", "device_id", "started_at", "finished_at"),
        "Snímka trasy": ("route_snapshot_json",),
    },
    "school_holidays": {
        "Prázdniny": ("name", "kind", "start_date", "end_date"),
        "Platnosť": ("city_id", "is_active", "id"),
    },
}


def section_of(resource: Resource, column: Column) -> str:
    for section, names in SECTIONS.get(resource.name, {}).items():
        if column.name in names:
            return section
    return "Údaje"


def metadata() -> list[dict]:
    return [
        {
            "name": resource.name,
            "label": resource.label,
            "group": resource.group,
            "pk": list(resource.pk),
            "title": resource.title,
            "search": bool(resource.search),
            "filters": list(resource.filters),
            "can_create": resource.can_create,
            "can_edit": resource.can_edit,
            "can_delete": resource.can_delete,
            "columns": [
                {
                    "name": column.name,
                    "label": column.label,
                    "kind": column.kind,
                    "editable": column.editable,
                    "required": column.required,
                    "in_list": column.in_list,
                    "ref": column.ref,
                    "choices": list(column.choices),
                    "choice_labels": {choice: CHOICE_LABELS.get(choice, choice) for choice in column.choices},
                    "help": column.help,
                    "default": column.default,
                    "section": section_of(resource, column),
                    "empty_text": column.empty_text,
                }
                for column in resource.columns
            ],
        }
        for resource in RESOURCES
    ]
