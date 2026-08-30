# FleetLink

FleetLink is a multiplayer Battleship desktop application developed for course 20503 - Advanced Programming Workshop in Java.

## Technologies

- Java 21
- JavaFX
- Java RMI
- Jakarta Persistence (JPA)
- Hibernate ORM
- H2 Database
- Apache Maven

## Project Structure

- `shared` - RMI interfaces, serializable DTOs and shared protocol types.
- `server` - authoritative game server, matchmaking, accounts, persistence, ratings, statistics and rematch coordination.
- `client` - JavaFX desktop application and RMI client integration.

## Requirements

- JDK 21
- Windows PowerShell
- No separate Maven installation is required; Maven Wrapper is included.

All commands below must be executed from the repository root.

## Build and Verify

```powershell
.\mvnw.cmd clean verify
```

## Start the Server

```powershell
.\mvnw.cmd -pl server -am package -DskipTests
$serverCp = Get-Content -Raw server\target\runtime-classpath.txt
java -cp "server\target\classes;shared\target\classes;$serverCp" io.github.tomerg12.fleetlink.server.rmi.FleetLinkServerMain
```

The default RMI port is `1099` and the service is bound as `FleetLinkServer`.

Keep the server terminal open while clients are running.

## Start a Client

Open another PowerShell terminal in the repository root:

```powershell
.\mvnw.cmd -pl client -am package dependency:build-classpath "-Dmdep.outputFile=target/runtime-classpath.txt" -DskipTests
$clientCp = Get-Content -Raw client\target\runtime-classpath.txt
java --module-path "$clientCp" --add-modules javafx.controls -cp "client\target\classes;shared\target\classes" io.github.tomerg12.fleetlink.client.ui.FleetLinkClientApplication
```

To run a second player, open another PowerShell terminal and run:

```powershell
$clientCp = Get-Content -Raw client\target\runtime-classpath.txt
java --module-path "$clientCp" --add-modules javafx.controls -cp "client\target\classes;shared\target\classes" io.github.tomerg12.fleetlink.client.ui.FleetLinkClientApplication
```

## Database

FleetLink uses a file-backed H2 database.

When the server is started from the repository root, the database is stored at:

```text
data/fleetlink.mv.db
```

The database is created automatically and is not included in the submitted source package.

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

## Tests

The project includes automated unit and integration tests in all three modules.

Run:

```powershell
.\mvnw.cmd test
```

The final verified project baseline passed 385 automated tests.

## Documentation

The `Documentation` directory is reserved for the final submission documents:

- FleetLink functionality and user guide.
- FleetLink system design and architecture document.
