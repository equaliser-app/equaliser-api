# `equaliser-api`

This component implements the core functionality of Equaliser.

## Launch

You must have a JDK8 installed to run this component. If you don't, follow the README on the `master` branch. A snapshotted virtual machine is highly recommended to ease clearing up the various dependencies.

 1. Add an entry to your hosts file that points *api.equaliser.events* to 127.0.0.1.
 2. Execute `./gradlew shadowJar` at the project root.
 3. Execute `docker build -t equaliser/api:1.0.0 .` to create the image.
 4. Execute `docker run -p 8080:80 equaliser/api:1.0.0` to launch a new container based on the image.

This will spin up an API service on http://api.equaliser.events:8080. Try requesting `/countries` or `/series/showcase`.
