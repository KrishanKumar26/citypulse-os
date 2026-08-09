-- Open every module to VIEWER, the role granted on self-service signup.
--
-- A new account could reach eleven of the fourteen modules. AI Insights,
-- Anomaly Detection and the What-If Simulator were locked, so a visitor met a
-- rail where three entries existed only to say they were not allowed in. That
-- is defensible for a tenanted deployment and wrong for this one: the platform
-- is a demonstration, its data is synthetic and labelled as such, and the three
-- locked modules are the ones that show what it actually does — what a zone
-- normally does at this hour, which conditions co-occur, and what a scenario
-- would do to conditions that were really observed.
--
-- Read grants only. VIEWER is what anyone gets by signing up, so it must stay
-- unable to change anything; RbacConsistencyIT enforces that every permission it
-- holds has action 'read', and this migration keeps that true.
--
-- simulation:create is therefore deliberately absent. A visitor can open the
-- Simulator and read the scenarios that exist; running a new one writes a row
-- and stays with the roles an administrator assigns — ANALYST, CITY_OPERATOR
-- and FLEET_MANAGER already carry it.

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r
  JOIN permissions p ON p.name IN ('analytics:read', 'anomaly:read', 'simulation:read')
 WHERE r.name = 'VIEWER'
   AND r.deleted_at IS NULL
ON CONFLICT (role_id, permission_id) DO NOTHING;
