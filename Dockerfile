# ── Stage 1: Build frontend ───────────────────────────────────────────────────
FROM node:20-alpine AS frontend-build
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
# Vite 變數是 build 時烙進 bundle 的；Render 會把服務環境變數注入為 build args，
# 但需在此宣告 ARG 才吃得到。未設定時為空字串 = 登入功能隱藏（同以往行為）。
ARG VITE_GOOGLE_OAUTH_CLIENT_ID
RUN npm run build

# ── Stage 2: Build backend (with frontend static files bundled in) ─────────────
FROM maven:3.9-eclipse-temurin-17 AS backend-build
WORKDIR /app/backend
# Cache dependencies first
COPY backend/pom.xml ./
RUN mvn dependency:go-offline -q
# Copy source and inject compiled frontend
COPY backend/src ./src
COPY --from=frontend-build /app/frontend/dist ./src/main/resources/static
RUN mvn clean package -DskipTests -q

# ── Stage 3: Minimal runtime ──────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=backend-build /app/backend/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
