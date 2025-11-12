# Railway MySQL Deployment Checklist

When the application fails with `Communications link failure` during startup on Railway, it usually means that the service cannot reach the MySQL instance. Work through the steps below to verify the deployment configuration.

## 1. Confirm the correct environment variables

Railway normally injects one of the following variables into each service:

- `MYSQL_URL` or `MYSQL_PUBLIC_URL`
- `DATABASE_URL`
- `CLEARDB_DATABASE_URL`
- `JAWSDB_URL`
- `MYSQLHOST` / `MYSQLHOSTNAME`
- `MYSQLPORT`
- `MYSQLDATABASE`
- `MYSQLUSER` / `MYSQL_USER`
- `MYSQLPASSWORD` / `MYSQL_PASSWORD`

Open the service in the Railway dashboard and inspect the **Variables** tab. At least one of the connection-string variables (for example `MYSQL_URL`) or the host/port variables must be present. The application now logs a warning during startup when none of these values are detected, which helps verify whether the variables are visible to the container.

> Tip: If you renamed the variables or marked them as **Private**, make sure they are still scoped to the service running the Spring Boot application.

## 2. Verify the database instance is running

From the Railway project overview, ensure the MySQL database deployment reports a healthy status. If it is stopped or suspended, start it manually before redeploying the backend service.

## 3. Check networking settings

If the MySQL deployment only exposes a **private** hostname (for example `mysql.internal`), enable **Private Networking** for the backend service as well so both resources share the same internal network. Otherwise, use the public proxy host listed in `MYSQL_URL` (usually `*.proxy.rlwy.net`).

## 4. Test the connection from a Railway shell

Use the "Debug" → "Start Shell" option on the backend service and run a manual connection test:

```bash
mysql \
  -h "$MYSQLHOST" \
  -P "$MYSQLPORT" \
  -u "$MYSQLUSER" \
  -p"$MYSQLPASSWORD" \
  "$MYSQLDATABASE"
```

If the shell cannot reach the database, the failure is outside the Spring Boot application. Double-check firewall settings and confirm the database allows connections from the service.

## 5. Ensure the production profile is active

Set the environment variable `SPRING_PROFILES_ACTIVE=prod` in Railway so that the production properties and the Railway-specific auto-detection run during startup.

## 6. Review application logs

On successful startup you should see a log entry similar to:

```
Detected MySQL configuration from MYSQL_URL (host: mydb.proxy.rlwy.net, port: 3306, database: railway, user provided: true)
```

If this message is missing, double-check steps 1 and 5. If the message appears but the connection still fails, the host/port reported in the log is the exact target that refused the connection—compare it with the details shown for the database deployment in Railway.

Following the checklist above typically resolves `connection refused` errors when deploying to Railway.
