# FleetLink

FleetLink is a multiplayer Battleship desktop application developed for course 20503 - Advanced Programming Workshop in Java.

The system uses a JavaFX desktop client, Java RMI for client-server communication, an authoritative Java server, and JPA/Hibernate with a file-backed H2 database for persistent data.

## Technologies

- Java 21
- JavaFX 21.0.12
- Java RMI
- Jakarta Persistence (JPA) 3.1
- Hibernate ORM 6.6.54.Final
- H2 Database 2.4.240
- Apache Maven
- JUnit Jupiter 5.14.4

## Project Structure

The project is a Maven multi-module project containing three main modules:

- `shared` - RMI interfaces, serializable DTOs, enums and shared protocol types.
- `server` - authoritative game server, accounts, sessions, matchmaking, game coordination, persistence, rating, statistics and rematch handling.
- `client` - JavaFX desktop application and RMI client integration.

Production code is located under each module's `src/main` directory. Automated tests are included under `src/test`.

## Requirements

- JDK 21
- No separate Maven installation is required; Maven Wrapper is included in the repository.

The commands below are provided for Windows PowerShell and must be executed from the repository root. On Windows, make sure `JAVA_HOME` points to a JDK 21 installation.

## Build and Verify

To build the complete project and run the automated test suite:

```powershell
.\mvnw.cmd clean verify
```

## Start the Server

From the repository root:

```powershell
.\mvnw.cmd -pl server -am package -DskipTests
$serverCp = Get-Content -Raw server\target\runtime-classpath.txt
java -cp "server\target\classes;shared\target\classes;$serverCp" io.github.tomerg12.fleetlink.server.rmi.FleetLinkServerMain
```

By default, the RMI registry uses port `1099` and the service is bound as `FleetLinkServer`.

Keep the server terminal open while the clients are running.

## Start a Client

Open another PowerShell terminal in the repository root:

```powershell
.\mvnw.cmd -pl client -am package dependency:build-classpath "-Dmdep.outputFile=target/runtime-classpath.txt" -DskipTests
$clientCp = Get-Content -Raw client\target\runtime-classpath.txt
java --module-path "$clientCp" --add-modules javafx.controls -cp "client\target\classes;shared\target\classes" io.github.tomerg12.fleetlink.client.ui.FleetLinkClientApplication
```

To run a second player, open another PowerShell terminal in the repository root and run:

```powershell
$clientCp = Get-Content -Raw client\target\runtime-classpath.txt
java --module-path "$clientCp" --add-modules javafx.controls -cp "client\target\classes;shared\target\classes" io.github.tomerg12.fleetlink.client.ui.FleetLinkClientApplication
```

## Database

FleetLink uses a file-backed H2 database.

When the server is started from the repository root, the database is created automatically at:

```text
data/fleetlink.mv.db
```

The local database file is runtime data and is not included in the submitted source repository.

## Main Application Flow

```text
Login / Register / Guest
        ->
Lobby
        ->
Matchmaking
        ->
Ship Placement
        ->
Battle
        ->
Game Over
        ->
Lobby / Rematch
```

Registered users can also open Player Statistics from the Lobby. Guests can play without creating a persistent account.

## Tests

The project includes unit and integration tests across all three modules.

To run the test suite:

```powershell
.\mvnw.cmd test
```

The final verified project baseline passed 385 automated tests:

- `shared`: 86
- `server`: 154
- `client`: 145

## Documentation

The `Documentation` directory contains the final project submission documents:

- FleetLink functionality and user guide.
- FleetLink system design and architecture document.
