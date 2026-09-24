ALTER TABLE user_accounts DROP CONSTRAINT ck_user_accounts_role;

ALTER TABLE user_accounts ADD CONSTRAINT ck_user_accounts_role
    CHECK (role IN ('OPERATOR', 'VIEWER', 'ADMIN'));
