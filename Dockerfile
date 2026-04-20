# Multi-stage build: compile deps first, then slim runtime image.
#
# Build:  docker build -t chachaml .
# Run:    docker run -p 8080:8080 -v chachaml-data:/data chachaml
#
# For team use with Postgres, see docker-compose.yml.

# --- Stage 1: build deps + uberjar -----------------------------------
FROM clojure:temurin-21-tools-deps-bookworm-slim AS builder

WORKDIR /build
COPY deps.edn build.clj ./
# Pre-fetch all dependencies (cached layer)
RUN clojure -P && clojure -P -M:ui

COPY src/ src/
COPY resources/ resources/

# Build a library jar (we run via clojure CLI, not an uberjar)
RUN clojure -T:build jar

# --- Stage 2: runtime -------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

RUN useradd -m -s /bin/bash chachaml

WORKDIR /app
COPY --from=builder /root/.m2 /home/chachaml/.m2
COPY --from=builder /build /app

# Install Clojure CLI (slim, no build tools needed at runtime)
RUN apt-get update && apt-get install -y --no-install-recommends curl rlwrap && \
    curl -sL https://download.clojure.org/install/linux-install-1.12.0.1530.sh | bash && \
    apt-get remove -y curl && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

USER chachaml

# Default: UI server on port 8080, SQLite at /data/chachaml.db
# Override DB_TYPE, JDBC_URL etc. via environment for Postgres.
ENV PORT=8080
ENV DB_PATH=/data/chachaml.db
ENV ARTIFACT_DIR=/data/artifacts

EXPOSE 8080

VOLUME ["/data"]

# Entrypoint uses environment variables to configure the store
COPY <<'ENTRYPOINT' /app/entrypoint.clj
(require '[chachaml.store :as store]
         '[chachaml.ui.server :as ui])

(let [db-type (or (System/getenv "DB_TYPE") "sqlite")
      port    (parse-long (or (System/getenv "PORT") "8080"))
      opts    (case db-type
                "sqlite"   {:type :sqlite
                            :path (or (System/getenv "DB_PATH") "/data/chachaml.db")
                            :artifact-dir (or (System/getenv "ARTIFACT_DIR") "/data/artifacts")}
                "postgres" {:type :postgres
                            :jdbc-url     (System/getenv "JDBC_URL")
                            :username     (System/getenv "DB_USER")
                            :password     (System/getenv "DB_PASSWORD")
                            :artifact-dir (or (System/getenv "ARTIFACT_DIR") "/data/artifacts")})]
  (ui/start! (merge opts {:port port :join? true})))
ENTRYPOINT

CMD ["clojure", "-M:ui", "-i", "/app/entrypoint.clj"]
