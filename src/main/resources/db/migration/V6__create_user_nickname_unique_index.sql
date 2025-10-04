alter table users
    add constraint ux_users_nickname unique (nickname);