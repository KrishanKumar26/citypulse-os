-- =============================================================================
-- V2 — Seed the RBAC model: permissions, the seven system roles (PRD §5),
--      and their grants.
--
-- This is reference data the application depends on, not demo data. Permissions
-- for modules that arrive in later phases are seeded now so role definitions do
-- not need to be re-migrated each phase.
--
-- Permission constants live in com.citypulse.user.domain.Permissions and are
-- asserted against this table by RbacConsistencyIT, so a name that exists in
-- only one place fails the build.
-- =============================================================================

INSERT INTO permissions (uid, name, resource, action, description) VALUES
    -- Users and access control
    (gen_random_uuid(), 'user:read',           'user',       'read',         'View user accounts'),
    (gen_random_uuid(), 'user:write',          'user',       'write',        'Create and modify user accounts'),
    (gen_random_uuid(), 'user:manage_roles',   'user',       'manage_roles', 'Assign and remove roles'),
    (gen_random_uuid(), 'role:read',           'role',       'read',         'View roles and their permissions'),

    -- Geography
    (gen_random_uuid(), 'city:read',           'city',       'read',         'View cities'),
    (gen_random_uuid(), 'city:write',          'city',       'write',        'Create, update and remove cities'),
    (gen_random_uuid(), 'zone:read',           'zone',       'read',         'View zones'),
    (gen_random_uuid(), 'zone:write',          'zone',       'write',        'Create, update and remove zones'),

    -- Intelligence
    (gen_random_uuid(), 'telemetry:read',      'telemetry',  'read',         'View live city telemetry'),
    (gen_random_uuid(), 'forecast:read',       'forecast',   'read',         'View forecasts'),
    (gen_random_uuid(), 'anomaly:read',        'anomaly',    'read',         'View detected anomalies'),
    (gen_random_uuid(), 'alert:read',          'alert',      'read',         'View alerts'),
    (gen_random_uuid(), 'alert:manage',        'alert',      'manage',       'Acknowledge, investigate and resolve alerts'),
    (gen_random_uuid(), 'simulation:read',     'simulation', 'read',         'View what-if simulations and results'),
    (gen_random_uuid(), 'simulation:create',   'simulation', 'create',       'Run what-if simulations'),
    (gen_random_uuid(), 'analytics:read',      'analytics',  'read',         'View historical analytics'),
    (gen_random_uuid(), 'analytics:export',    'analytics',  'export',       'Export analytics datasets and reports'),

    -- Platform administration
    (gen_random_uuid(), 'datasource:read',     'datasource', 'read',         'View data sources and their health'),
    (gen_random_uuid(), 'datasource:manage',   'datasource', 'manage',       'Configure data sources'),
    (gen_random_uuid(), 'apikey:read',         'apikey',     'read',         'View API keys and usage'),
    (gen_random_uuid(), 'apikey:manage',       'apikey',     'manage',       'Create and revoke API keys'),
    (gen_random_uuid(), 'audit:read',          'audit',      'read',         'View the audit log'),
    (gen_random_uuid(), 'system:manage',       'system',     'manage',       'Manage platform-wide configuration');

INSERT INTO roles (uid, name, display_name, description, system_role) VALUES
    (gen_random_uuid(), 'SUPER_ADMIN',   'Super Administrator', 'Unrestricted access to every capability, including platform configuration', TRUE),
    (gen_random_uuid(), 'ADMIN',         'Administrator',       'Manages users, cities, data sources and API keys', TRUE),
    (gen_random_uuid(), 'CITY_OPERATOR', 'City Operator',       'Monitors the city, manages alerts and runs simulations', TRUE),
    (gen_random_uuid(), 'ANALYST',       'Data Analyst',        'Explores historical analytics and exports reports', TRUE),
    (gen_random_uuid(), 'FLEET_MANAGER', 'Fleet Manager',       'Monitors traffic and route risk relevant to fleet operations', TRUE),
    (gen_random_uuid(), 'DEVELOPER',     'Developer',           'Consumes the platform API and manages own API keys', TRUE),
    (gen_random_uuid(), 'VIEWER',        'Viewer',              'Read-only access to city intelligence; assigned on signup', TRUE);

-- --------------------------------------------------------------------------
-- Grants. Written as name-based joins so the statements stay readable and
-- remain correct regardless of generated ids.
-- --------------------------------------------------------------------------

-- SUPER_ADMIN holds every permission, including any added by later migrations
-- that re-run this pattern.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p WHERE r.name = 'SUPER_ADMIN';

-- ADMIN: everything except platform-wide configuration.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name = 'ADMIN' AND p.name <> 'system:manage';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN (
    'city:read', 'zone:read', 'telemetry:read', 'forecast:read', 'anomaly:read',
    'alert:read', 'alert:manage', 'simulation:read', 'simulation:create', 'analytics:read')
WHERE r.name = 'CITY_OPERATOR';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN (
    'city:read', 'zone:read', 'telemetry:read', 'forecast:read', 'anomaly:read',
    'alert:read', 'simulation:read', 'simulation:create', 'analytics:read', 'analytics:export')
WHERE r.name = 'ANALYST';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN (
    'city:read', 'zone:read', 'telemetry:read', 'forecast:read',
    'alert:read', 'simulation:read', 'simulation:create', 'analytics:read')
WHERE r.name = 'FLEET_MANAGER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN (
    'city:read', 'zone:read', 'telemetry:read', 'forecast:read',
    'alert:read', 'analytics:read', 'apikey:read', 'apikey:manage')
WHERE r.name = 'DEVELOPER';

-- VIEWER is the role granted on self-service signup: read-only until an
-- administrator elevates the account.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN (
    'city:read', 'zone:read', 'telemetry:read', 'forecast:read', 'alert:read')
WHERE r.name = 'VIEWER';
