-- Deploy the 6 service databases on first boot of the postgres volume.
-- Each service connects ONLY to its own database.

CREATE DATABASE auth_db;
CREATE DATABASE catalogue_db;
CREATE DATABASE messaging_db;
CREATE DATABASE notif_db;
CREATE DATABASE asset_db;
CREATE DATABASE admin_db;
