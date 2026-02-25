# Running E2E Tests on Windows

This guide provides step-by-step instructions for running the SDMX Proxy E2E tests on Windows.

## Prerequisites

### 1. Java Development Kit (JDK)

- **Required Version**: Java 25 (or compatible)
- **Installation**: Download and install from [Oracle JDK](https://www.oracle.com/java/technologies/downloads/)
  or [OpenJDK](https://adoptium.net/)
- **Verification**: Open Command Prompt or PowerShell and run:
  ```cmd
  java -version
  ```
  You should see version 25.x.x or compatible.

### 2. Docker Provider

You need a Docker daemon running on Windows. We recommend **Rancher Desktop** for the best compatibility with
Testcontainers.

#### Option A: Rancher Desktop (Recommended)

1. **Download**: Get Rancher Desktop from [https://rancherdesktop.io/](https://rancherdesktop.io/)
2. **Install**: Run the installer and follow the setup wizard
3. **Start**: Launch Rancher Desktop from the Start menu
4. **Verify**: Open Command Prompt or PowerShell and run:
   ```cmd
   docker ps
   ```
   You should see an empty list (or running containers) without errors.

#### Option B: WSL Docker - DO NOT USE IT. IT WORKS INCORRECTLY WITH Testcontainers

### 3. Gradle Wrapper

The project includes a Gradle Wrapper (`gradlew` or `gradlew.bat`), so you don't need to install Gradle separately.

## Environment Variables

Set the following environment variables before running the tests. These are required if you're pulling Docker images
from a private registry.

### Required Variables (for private registries)

| Variable                | Description                            | Example                     |
|-------------------------|----------------------------------------|-----------------------------|
| `DOCKER_IMAGE_REGISTRY` | Docker registry URL (without protocol) | `registry.example.com:8083` |
| `DOCKER_IMAGE_TAG`      | Docker image tag to use                | `1.0.0` or `latest`         |
| `E2E_DOCKER_USER`       | Username for Docker registry login     | `your-username`             |
| `E2E_DOCKER_PASS`       | Password for Docker registry login     | `your-password`             |

## Running the Tests

### From Command Line

#### Using Gradle Wrapper (Windows)

```cmd
cd sdmx-proxy-e2e
..\gradlew.bat test
```

### From IntelliJ IDEA

1. **Open Project**: Open the project in IntelliJ IDEA
2. **Wait for Gradle Sync**: Let Gradle sync complete
3. **Run Tests**:
    - **Option A**: Right-click on `sdmx-proxy-e2e/src/test/java` → **Run 'All Tests'**
    - **Option B**: Open a test class (e.g., `HealthCheckTests.java`) → Click the green play button next to the class
      name
    - **Option C**: Run a specific test method by clicking the green play button next to the method

## How It Works

1. **Container Startup**: The `ContainerFixture` JUnit extension starts a Docker container with the `sdmx-proxy` image
   before any tests run
2. **Health Check**: The container waits until the application's health endpoint responds successfully
3. **Test Execution**: Tests run against the running container
4. **Container Cleanup**: The container is automatically stopped after all test classes complete

## Troubleshooting

### Issue: "Cannot run program 'docker': CreateProcess error=2"

**Cause**: Java cannot find the `docker` command in the system PATH.

**Solutions**:

1. **Verify Docker is accessible**:
   ```cmd
   docker ps
   ```
   If this fails, Docker is not properly installed or not in PATH.

2. **Add Docker to PATH**:
    - For Rancher Desktop: Usually added automatically. If not, add `C:\Program Files\Rancher Desktop\resources\bin` to
      PATH
    - For Docker Desktop: Usually added automatically. If not, add `C:\Program Files\Docker\Docker\resources\bin` to
      PATH

3. **Restart IDE/terminal** after updating PATH

### Issue: "DOCKER_HOST tcp://localhost:2375 is not listening"

**Cause**: Testcontainers cannot connect to the Docker daemon.

**Solutions**:

1. **Verify Docker is running**:
   ```cmd
   docker ps
   ```

2. **Check Docker Desktop/Rancher Desktop status**: Ensure the application is running and Docker is started

3. **For Rancher Desktop**: Ensure it's using the default Docker context (not Kubernetes)

4. **Restart Docker**: Stop and start Docker Desktop/Rancher Desktop

### Issue: "Docker login failed"

**Cause**: The `docker login` command cannot authenticate with the private registry.

**Solutions**:

1. **Verify credentials**: Check that `E2E_DOCKER_USER` and `E2E_DOCKER_PASS` are set correctly

2. **Test Docker login manually**:
   ```cmd
   docker login --username %E2E_DOCKER_USER% --password %E2E_DOCKER_PASS% %DOCKER_IMAGE_REGISTRY%
   ```

3. **Check registry URL**: Ensure `DOCKER_IMAGE_REGISTRY` does not include `https://` prefix (it should be like
   `nexus.example.com:8083`)

4. **Verify network access**: Ensure you can reach the registry from your machine

### Issue: "Connection refused: connect" during tests

**Cause**: The container was stopped prematurely or the application inside the container is not ready.

**Solutions**:

1. **Check container logs**: Look for errors in the test output or container logs
2. **Verify health endpoint**: The container should wait for the health endpoint to be ready before tests run
3. **Check port conflicts**: Ensure no other application is using the ports that Testcontainers assigns

### Issue: Tests pass "through half the time" or intermittently fail

**Cause**: This was a known issue where the container was stopped after each test class. This has been fixed by
implementing a reference counter in `ContainerFixture`.

**Solutions**:

1. **Ensure you're using the latest code**: Pull the latest changes
2. **Check test execution order**: All test classes should use `@ExtendWith(ContainerFixture.class)`
3. **Run tests sequentially**: Avoid running tests in parallel if you encounter this issue

### Issue: "invalid reference format" when pulling Docker image

**Cause**: The Docker image name includes an invalid protocol prefix (e.g., `https://`).

**Solutions**:

1. **Check `DOCKER_IMAGE_REGISTRY`**: Ensure it does not include `https://` or `http://`
    - ❌ Wrong: `https://registry.example.com:8083`
    - ✅ Correct: `registry.example.com:8083`

2. **The code automatically strips the protocol**, but ensure your environment variable is set correctly

### Issue: Tests fail with "Container has not been started"

**Cause**: Tests are trying to access the container before `ContainerFixture` has started it.

**Solutions**:

1. **Ensure `@ExtendWith(ContainerFixture.class)` is present** on your test class:
   ```java
   @ExtendWith(ContainerFixture.class)
   class YourTestClass {
       // ...
   }
   ```

2. **Do not initialize `RestClient` in constructor**: Use `@BeforeAll` instead:
   ```java
   @BeforeAll
   void setUp() {
       this.restClient = new RestClient(ContainerFixture.getBaseUrl());
   }
   ```

## Additional Notes

### Docker Image Configuration

The tests use the following defaults if environment variables are not set:

- **Image Name**: `statgpt/statgpt-sdmx-proxy`
- **Tag**: `latest`
- **Registry**: None (assumes Docker Hub or local registry)

You can override these via:

- **Environment variables** (recommended): `DOCKER_IMAGE_REGISTRY`, `DOCKER_IMAGE_TAG`
- **System properties**: `-Ddocker.image.registry=...`, `-Ddocker.image.tag=...`

### Test Reports

Test results are generated in:

- **JUnit XML**: `build/test-results/e2e/`
- **HTML Reports**: `build/reports/tests/test/` (if configured)

### Performance Tips

1. **Reuse containers**: The `ContainerFixture` reuses the same container across all test classes to speed up execution
2. **Parallel execution**: By default, tests run sequentially. If you want parallel execution, configure it in
   `build.gradle`
3. **Docker image caching**: Keep the Docker image cached locally to avoid pulling it on every test run

## Support

If you encounter issues not covered in this guide:

1. Check the test logs for detailed error messages
2. Verify all prerequisites are installed and configured correctly
3. Ensure Docker is running and accessible
4. Check that environment variables are set correctly
5. Review the container logs (available in test output) for application-level errors
