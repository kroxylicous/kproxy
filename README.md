# Kroxylicious

Kroxylicious is an exploration for building a Kafka protocol proxy,
addressing use cases such as multi-tenancy, schema validation, or encryption.

# Setting Up in Windows Using WSL
### For installing WSL (Skip the First '4' steps if already done)
1. Enable the Windows Subsystem for Linux feature: To enable WSL, you need to enable the Windows Subsystem for Linux feature in the Windows Features dialog.


2. Install a Linux distribution from the Microsoft Store. The rest of these instructions assume a distribution (such as Ubuntu) which uses `apt` for package management, but the process should be similar for distributions using other package managers/ such as Fedora/`dnf`.

3. Launch the Linux distribution and Configure the Linux environment: After launching the Linux distribution, you can configure the environment by creating a user account and setting the password.

With these steps, you should now have WSL installed and configured on your Windows system. 

For further assistance please see the [Microsoft documentation](https://learn.microsoft.com/en-us/windows/wsl/install)

4. Open the WSL window.


5. Install Latest Java version by typing 
```bash
java --version
```
and Choose the version (preferably v18), it should look like:
```bash
sudo apt install openjdk-18-jre-headless
```
6. Update the packages using
```bash
  sudo apt update
  sudo apt upgrade
```

7. Navigate to kroxylicious folder using :
```bash
 cd kroxylicious
 ```


## Build

Java, version 11 or newer, and Apache Maven are required for building this project.
Build the project like this:

```
$ mvn clean install
```

The running of the tests can be controlled with the following Maven properties:

| property           | description            |
|--------------------|------------------------|
| `-DskipUTs=true`   | skip unit tests        |
| `-DskipITs=true`   | skip integration tests |
| `-DskipTests=true` | skip all tests         |

The kafka environment used by the integrations tests can be _defaulted_ with these two environment variables.

| env var                       | default | description                                                                                                                             |
|-------------------------------|---------|-----------------------------------------------------------------------------------------------------------------------------------------|
| `TEST_CLUSTER_EXECUTION_MODE` | `IN_VM` | `IN_VM` or `CONTAINER`. if `IN_VM`, kafka will be run same virtual machines as the integration test. Otherwise containers will be used. |
| `TEST_CLUSTER_KRAFT_MODE`     | `true`  | if true, kafka will be run in kraft mode.                                                                                               |

When the integration-tests are run in `CONTAINER` mode, the kafka/zookeeper logs are written to a location specified by
the `container.logs.dir`  system property. When run through Maven this is defaulted to `integrationtests/target/container-logs`.

Pass the `-Dquick` option to skip all tests and non-essential plug-ins and create the output artifact as quickly as possible:

```
$ mvn clean verify -Dquick
```

Run the following command to format the source code and organize the imports as per the project's conventions:

```
$ mvn process-sources
```

Build with the `dist` profile for creating an executable JAR:

```
$ mvn clean verify -Pdist -Dquick
```

Run the following to add missing license headers e.g. when adding new source files:

```
$ mvn org.commonjava.maven.plugins:directory-maven-plugin:highest-basedir@resolve-rootdir license:format
```

## Run

Build with the `dist` profile as shown above, then execute this:

```
$ java -jar kroxylicious/target/kroxylicious-1.0-SNAPSHOT.jar -cp {path-to-your-class-path}
```

To prevent the [following error](https://www.slf4j.org/codes.html#StaticLoggerBinder):

```
Failed to load class org.slf4j.impl.StaticLoggerBinder
```

Make sure to follow the [suggestions here](https://www.slf4j.org/codes.html#StaticLoggerBinder) to include one (and only one) of the suggested jars on the classpath.

## Rendering documentation

The `docs` directory has some user documentation written in [AsciiDoc](https://docs.asciidoctor.org/asciidoc/latest/) format.
You can render it to HTML using:

```
./mvnw org.asciidoctor:asciidoctor-maven-plugin:process-asciidoc@convert-to-html
```

The output will be in `target/html/master.html`. 

## Performance Testing

See [benchmarking.md](benchmarking.md) for information on running basic performance tests for this proxy.

## Architecture Monitoring

This project uses [Deptective 🕵](https://github.com/moditect/deptective/) for monitoring its architecture and fails any violations,
either in form of unwanted package dependencies or circular package dependencies.
The target dependency model is configured in the [deptective.json](kroxylicious/src/main/resources/META-INF/deptective.json) file.
Any new package relationships need to be registered there.

To verify whether the code base adheres to that target model, run the following:

```
$ mvn clean verify -Dquick -Parchitecture-check -pl kroxylicious
```

In case of any architecture violations, the actual architecture can be visualized using GraphViz like so:

```
$ dot -Tpng kroxylicious/target/generated-sources/annotations/deptective.dot > kroxylicious-arch.png
```

## IntelliJ setup

Currently the project uses JDK 11 for the actual code and JDK 17 for tests. 
IntelliJ needs to be configured to use the "new Workspace Model" in order for it to understand this
(and to be useful for things like debugging):

1. Open the Settings dialog (`File > Settings`)
2. Navigate to `Build, Execution, Deployment > Build tools > Maven > Importing`.
3. Tick "Import using the new IntelliJ Workspace Model API (experimental)" 

## License

This code base is available under the Apache License, version 2.
