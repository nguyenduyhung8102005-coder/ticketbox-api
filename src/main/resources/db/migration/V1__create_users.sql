-- =====================================================================
-- V1: Module Auth/User
-- =====================================================================

CREATE TABLE users (
    id             BIGSERIAL    PRIMARY KEY,
    email          VARCHAR(255) NOT NULL,
    phone          VARCHAR(20),
    password_hash  VARCHAR(255) NOT NULL,
    full_name      VARCHAR(150) NOT NULL,
    role           VARCHAR(20)  NOT NULL DEFAULT 'CUSTOMER',
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_users_role   CHECK (role   IN ('CUSTOMER', 'STAFF', 'ADMIN')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DELETED'))
);

-- Email không phân biệt hoa thường: An@Gmail.com và an@gmail.com là một
CREATE UNIQUE INDEX uk_users_email_lower ON users (lower(email));
CREATE UNIQUE INDEX uk_users_phone       ON users (phone) WHERE phone IS NOT NULL;

COMMENT ON TABLE  users               IS 'Tài khoản người dùng (khách hàng, nhân viên soát vé, quản trị)';
COMMENT ON COLUMN users.password_hash IS 'Mật khẩu đã băm bằng BCrypt, không bao giờ lưu plain text';
