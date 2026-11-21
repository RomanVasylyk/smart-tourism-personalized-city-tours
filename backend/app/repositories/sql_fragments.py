EFFECTIVE_OPENING_HOURS = "COALESCE(p.opening_hours_override, p.opening_hours_raw) AS opening_hours_raw"
EFFECTIVE_OPENING_HOURS_SOURCE = "CASE WHEN p.opening_hours_override IS NOT NULL THEN 'admin' ELSE p.opening_hours_source END AS opening_hours_source"
