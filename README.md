# Employee Management System

Java REST API + JDBC database (H2, file-based) + HTML/CSS/JS dashboard.

## 1. Get the database driver (one-time)
Download the H2 jar into this folder:
https://repo1.maven.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar

## 2. Compile
    javac Server.java ApiTest.java

## 3. Run (JDK 17+)
Windows:      java -cp ".;h2-2.2.224.jar" Server
Mac / Linux:  java -cp ".:h2-2.2.224.jar" Server

Open http://localhost:8080 — data is saved in `./data/employees.mv.db` and survives restarts.

## 4. Test (server running, second terminal)
    java ApiTest

## API
| Method | Path | Description | Success |
|---|---|---|---|
| GET | /api/employees?search=&department= | List / filter | 200 |
| POST | /api/employees | Create | 201 |
| GET | /api/employees/{id} | Read | 200 |
| PUT | /api/employees/{id} | Update | 200 |
| DELETE | /api/employees/{id} | Delete | 204 |
| GET | /api/employees/stats | Totals, average salary, per department | 200 |

Errors: 400 malformed JSON / bad id, 404 not found, 405 bad method, 409 duplicate email,
415 wrong content type, 422 validation failed, 500 server error.
Body: `{"error":{"status":422,"message":"Validation failed","details":["email is required"]}}`

## Other databases
Set `DB_URL`, `DB_USER`, `DB_PASS` environment variables and put that database's JDBC jar on the
classpath (MySQL works as-is; PostgreSQL needs `SERIAL` instead of `INT AUTO_INCREMENT` in Server.java).
