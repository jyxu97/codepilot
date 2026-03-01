FROM python:3.13-slim

# git                — clone repos into workspaces
# tar                — workspace snapshot / restore
# default-jdk-headless — Java 17 (Debian Bookworm); compiles Java 11 source
# maven              — run 'mvn test' inside the sandbox for benchmark projects
RUN apt-get update && apt-get install -y --no-install-recommends \
        git \
        tar \
        default-jdk-headless \
        maven \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install Python dependencies first (cached layer — only invalidated when
# requirements.txt changes, not when source code changes)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application source
COPY . .

# Create a non-root user WITH a home directory so Maven can persist
# downloaded artifacts to ~/.m2 between agent turns within one container run.
RUN useradd --system --create-home --shell /bin/sh executor \
    && mkdir -p /home/executor/.m2 \
    && chown -R executor:executor /home/executor/.m2
USER executor

EXPOSE 8001

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
