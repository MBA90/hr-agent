# HR Recruitment Agent

An AI-powered HR recruitment assistant built with **Spring Boot 3**, **LangChain4j**, **Ollama**, **ChromaDB**, and **Oracle 19c**. The agent handles the full recruitment lifecycle — from parsing CVs and scoring candidates to scheduling interviews and sending emails — through a natural-language chat interface. Uploaded CVs are automatically embedded into a persistent vector store for semantic search and retrieval.

---

## Features

| Capability | Description |
|---|---|
| **JWT Authentication** | Stateless auth with signed JWTs (HS256, 24 h expiry); all agent endpoints require `Authorization: Bearer <token>` |
| **Role-Based Access Control** | Two roles — `ROLE_ADMIN` and `ROLE_RECRUITER`; roles are embedded in the JWT and enforced per-request |
| **Password Management** | Change password (authenticated), forgot/reset password via email token (60-min expiry) |
| **CV Upload & Versioning** | CVs are stored as `APP_<id>_v<n>.pdf` (versioned, never overwritten). Re-upload is allowed only while the application is `APPLIED`; any later status locks it (`409 Conflict`) |
| **CV Vector Ingestion (RAG)** | Every uploaded CV is asynchronously split into semantic section-based chunks by `CvSectionSplitter` (max 1 500 chars per section; configurable overlap only between sub-chunks of an oversized section), BM25 keywords extracted per chunk, embedded with `nomic-embed-text` via Ollama, and indexed into ChromaDB. Each chunk carries `app_ref_no`, `candidate_name`, `candidate_email`, `job_id`, `job_title`, `cv_version`, `section_type`, `chunk_index`, and `keywords` metadata. Re-uploads replace previous vectors (idempotent per `app_ref_no`). Retrieval uses hybrid dense + BM25 re-rank fused via Reciprocal Rank Fusion (RRF) |
| **CV Parsing** | Extracts text from PDF CVs using PDFBox, then uses the LLM to parse skills, experience, education, nationality, and current role — stored as an immutable snapshot on the application |
| **Candidate Scoring** | LLM scores each application against job requirements (0–100) and recommends SHORTLIST / CONSIDER / REJECT |
| **Interview Scheduling** | Books interviews against an application ID with conflict detection, stores date/time/type/interviewer |
| **Email Notifications** | Sends interview invitations, rejection emails, offer letters, and password-reset links via Gmail SMTP |
| **Candidate Management** | Lists jobs and candidates, filters by score threshold, updates application statuses |
| **Job Posting Management** | Create, update, close, delete, search, and get stats on job postings via natural language |
| **Multi-Application Support** | One candidate can apply to multiple jobs — each application carries its own CV version, parsed profile, status, score, and timeline |
| **JSON API** | All tool results returned as structured JSON for easy frontend consumption |

---

## Tech Stack

- **Java 21** / **Spring Boot 3.2.5**
- **Spring Security 6** — stateless JWT filter chain, BCrypt (cost 12)
- **JJWT 0.12** — JWT signing / validation (HS256)
- **LangChain4j 1.15.1** — `AiServices`, `@Tool`, `OllamaChatModel`, `EmbeddingStoreIngestor`
- **langchain4j-chroma 1.15.1-beta25** — `ChromaEmbeddingStore` (Chroma V2 API)
- **Ollama** — runs `llama3.2:latest` (chat) and `nomic-embed-text` (embeddings) locally
- **ChromaDB 0.5.x+** — vector store for CV embeddings (Docker)
- **Oracle 19c** — schema managed by **Liquibase** (native Oracle SQL migrations)
- **PDFBox 3** — PDF text extraction
- **Spring Mail** — Gmail SMTP (interview notifications + password-reset emails)
- **Lombok**, **Jackson**, **JUnit 5 + Mockito**

---

## Architecture

```
React Frontend
      │  Authorization: Bearer <JWT>
      │  POST /api/agent/chat  (ChatRequest)
      ▼
JwtAuthFilter  ──validates token──▶  SecurityContext
      ▼
AgentController
      │  calls HrAgentService
      │  publishes CvUploadedEvent (on CV upload)
      ▼
HrAgentService  (LangChain4j AiServices proxy)
      │  tool calls dispatched automatically by the LLM
      ├── CandidateTool      — job/candidate/application queries & status updates
      ├── CvParserTool       — PDF → LLM → structured profile snapshot
      ├── ScoringTool        — LLM scoring of applications against job requirements
      ├── SchedulerTool      — interview booking & management
      ├── EmailTool          — SMTP notifications
      └── JobPostingTool     — create / update / close / delete / search job postings
             │
             ▼  ThreadLocal capture (ToolResultContext)
AgentController  assembles ChatResponse { message, data }
```

### RAG Ingestion Pipeline

Every CV upload triggers an asynchronous ingestion flow decoupled from the HTTP request:

```
POST /api/agent/upload-cv
      │  file saved to ./cv-uploads/APP_<id>_v<n>.pdf
      │  Application saved to Oracle DB
      │
      ▼  ApplicationEventPublisher.publishEvent(CvUploadedEvent)
      │
      ▼  (async — separate thread)
CvIngestionListener (@Async @EventListener)
      │  loads Application (with candidate + job) from DB
      │  extracts raw text via PdfTextExtractor (PDFBox)
      │
      ▼
ChromaCvVectorStore  (CvVectorStore port)
      │  removeAll(app_ref_no == X)   ← idempotent: clears old vectors on re-upload
      │
      ▼
CvSectionSplitter
      │  normalizeInlineHeaders()     ← fixes PDFBox collapsed headers
      │  detectSections()             ← accumulates text per CV section (EXPERIENCE, SKILLS, …)
      │  splitIfOversized()           ← sub-splits sections > 1 500 chars (150-char overlap
      │                                  between sub-chunks; no spurious trailing overlap chunk)
      │  BM25Scorer.extractKeywords() ← top-15 keywords per chunk (sparse path)
      │
      ▼  per CvChunk — content + section_type + chunk_index + keywords
OllamaEmbeddingModel (nomic-embed-text, 768-dim)
      │  embeds each chunk individually
      ▼
ChromaEmbeddingStore  (ChromaDB, V2 API)
      │  metadata: app_ref_no, candidate_name, candidate_email,
      │            job_id, job_title, cv_version,
      │            section_type, chunk_index, section_chunk_index, keywords
      collection: cv-store

Retrieval (CvVectorStore.search / searchForApplication):
      dense top-3K  →  BM25 re-rank  →  Reciprocal Rank Fusion (RRF k=60)  →  top-K results
```

A Chroma or Ollama outage during ingestion only loses the vector copy (logged as WARN) — the HTTP upload and Oracle DB record always succeed.

Authentication flow:

```
POST /api/auth/register  or  /api/auth/login
        │
        ▼  AuthService → BCrypt verify → JwtUtil.generateToken()
        ▼
AuthResponse { token, tokenType, userId, username, email, fullName, roles }
```

---

## Database Schema

```
 ┌──────────────────────┐               ┌───────────────────────────┐               ┌─────────────────────┐
 │      JOB_POSTING     │               │        APPLICATION        │               │      CANDIDATE      │
 ├──────────────────────┤               ├───────────────────────────┤               ├─────────────────────┤
 │ PK  id               │  1        *   │ PK  id                    │   *        1  │ PK  id              │
 │     title            │◄──────────────│ FK  job_posting_id        │───────────────│ UQ  cnd_ref_no      │
 │     department       │               │ FK  candidate_id          │──────────────►│     full_name       │
 │     description      │               │ UQ  app_ref_no            │               │ UQ  email           │
 │     required_skills  │               │ UQ  (candidate,job)       │               │     phone           │
 │     experience_years │               │     status                │               │     updated_at      │
 │     location         │               │     score                 │               └─────────────────────┘
 │     salary_min       │               │     score_reason          │
 │     salary_max       │               │     applied_at            │   ← CV snapshot (frozen at review) →
 │     status           │               │     cv_file_path          │
 │     created_at       │               │     cv_version            │
 │     updated_at       │               │     nationality           │
 └──────────────────────┘               │     skills                │
                                        │     experience_years      │
                                        │     education             │
                                        │     current_role          │
                                        │     updated_at            │
                                        └───────────┬───────────────┘
                                                    │ 1
                                                    │ *
                                       ┌────────────▼────────────┐
                                       │        INTERVIEW        │
                                       ├─────────────────────────┤
                                       │ PK  id                  │
                                       │ FK  application_id      │
                                       │     scheduled_at        │
                                       │     duration_minutes    │
                                       │     interview_type      │
                                       │     meeting_link        │
                                       │     interviewer_name    │
                                       │     notes               │
                                       │     feedback            │
                                       │     status              │
                                       │     created_at          │
                                       └─────────────────────────┘


 ┌─────────────────────────┐               ┌──────────────────┐               ┌────────────────┐
 │        APP_USER         │               │    USER_ROLE     │               │    APP_ROLE    │
 ├─────────────────────────┤               ├──────────────────┤               ├────────────────┤
 │ PK  id                  │  1        *   │ PK,FK  user_id   │   *        1  │ PK  id         │
 │ UQ  username            │◄──────────────│ PK,FK  role_id   │──────────────►│ UQ  name       │
 │ UQ  email               │               │        assigned_at│               │     description│
 │     password_hash       │               └──────────────────┘               └────────────────┘
 │     full_name           │
 │     enabled             │
 │     created_at          │
 │     updated_at          │
 └────────────┬────────────┘
              │ 1
              │ *
 ┌────────────▼────────────┐
 │  PASSWORD_RESET_TOKEN   │
 ├─────────────────────────┤
 │ PK  id                  │
 │ UQ  token               │
 │ FK  user_id             │
 │     expires_at          │
 │     used                │
 │     created_at          │
 └─────────────────────────┘
```

---

## Prerequisites

| Requirement | Notes |
|---|---|
| Java 21+ | |
| Maven 3.9+ | |
| Oracle 19c | Schema: `hr_agent` / password: your choice |
| Ollama | Install from [ollama.com](https://ollama.com); pull both models (see below) |
| ChromaDB | Run via Docker Compose (see below) |
| Gmail App Password | Required for SMTP — see setup below |

---

## Setup

### 1. Oracle Database

```sql
CREATE USER hr_agent IDENTIFIED BY your_password;
GRANT CONNECT, RESOURCE, CREATE SESSION TO hr_agent;
GRANT UNLIMITED TABLESPACE TO hr_agent;
```

Liquibase runs all migrations automatically on startup.

### 2. Ollama

```bash
# Chat model (used by the HR agent)
ollama pull llama3.2

# Embedding model (used for CV vector ingestion)
ollama pull nomic-embed-text

ollama serve   # starts on http://localhost:11434
```

### 3. ChromaDB

Start ChromaDB and its admin UI with Docker Compose:

```yaml
# docker-compose.yml
services:
  chromadb:
    image: chromadb/chroma:0.5.23
    container_name: chromadb
    volumes:
      - chroma_data:/chroma/chroma
    ports:
      - "8000:8000"
    environment:
      - ANONYMIZED_TELEMETRY=FALSE
    restart: unless-stopped

  chromadb-ui:
    image: thanatosdi/chromadb-admin:latest
    container_name: chromadb-ui
    ports:
      - "3000:3000"
    depends_on:
      - chromadb
    restart: unless-stopped

volumes:
  chroma_data:
```

```bash
docker compose up -d
```

The `cv-store` collection is created automatically on the first CV upload — no manual setup needed.

> **Admin UI:** open `http://localhost:3000` and set the ChromaDB URL to `http://chromadb:8000.

Verify Chroma is up:

```bash
curl http://localhost:8000/api/v2/heartbeat
# → {"nanosecond heartbeat": ...}

curl http://localhost:8000/api/v2/tenants/default_tenant/databases/default_database/collections
# → [] before first upload, ["cv-store"] after
```

### 4. Gmail App Password

1. Enable 2-Step Verification on your Google account
2. Go to **Google Account → Security → App Passwords**
3. Create a password for "Mail"
4. Use the 16-character password in `application-local.yml`

### 5. Configuration

Create `src/main/resources/application-local.yml` (git-ignored):

```yaml
spring:
  datasource:
    url: jdbc:oracle:thin:@localhost:1521/ORCLPDB1
    username: hr_agent
    password: YOUR_DB_PASSWORD

  mail:
    username: your.email@gmail.com
    password: YOUR_GMAIL_APP_PASSWORD

hr:
  agent:
    cv-storage-path: /path/to/your/cv-uploads/
  password-reset:
    reset-url: http://localhost:3000/reset-password

security:
  jwt:
    secret: YOUR_SECRET_MIN_32_CHARS   # openssl rand -hex 32
    expiration: 86400000               # 24 h in milliseconds
```

**Environment variables** (alternative to local yml):

| Variable | Default | Description |
|---|---|---|
| `JWT_SECRET` | *(required)* | HS256 signing key, min 32 chars |
| `DB_PASSWORD` | `admin` | Oracle schema password |
| `MAIL_USERNAME` | — | Gmail address |
| `MAIL_PASSWORD` | — | Gmail App Password |
| `CV_STORAGE_PATH` | `./cv-uploads/` | Local directory for CV files |
| `PASSWORD_RESET_URL` | `http://localhost:3000/reset-password` | Frontend reset link |
| `CHROMA_URL` | `http://localhost:8000` | ChromaDB base URL |
| `CHROMA_COLLECTION` | `cv-store` | ChromaDB collection name |
| `OLLAMA_EMBED_MODEL` | `nomic-embed-text` | Embedding model pulled in Ollama |

### 6. Run

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The API will be available at `http://localhost:8080`.

---

## API Reference

A ready-to-import Postman collection covering every endpoint is included:

```
src/main/resources/postman-collections/HR Agent Collection.postman_collection.json
```

> All `/api/agent/*` endpoints require `Authorization: Bearer <token>`.  
> Auth endpoints are public.

### Auth endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/auth/register` | No | Create a new recruiter account |
| `POST` | `/api/auth/login` | No | Log in and receive a JWT |
| `POST` | `/api/auth/change-password` | Yes | Change password (current password required) |
| `POST` | `/api/auth/forgot-password` | No | Send a password-reset link to the account email |
| `POST` | `/api/auth/reset-password` | No | Reset password using the emailed token (valid 60 min) |

A default admin account is seeded on first startup — username `admin`, password `Admin@1234`.

### Agent endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/agent/chat` | Yes | Send a natural-language message to the HR agent |
| `POST` | `/api/agent/upload-cv` | Yes | Upload a PDF CV (`file`, `jobId`, `name`, `email`, `phone`). File stored as `APP_<id>_v<n>.pdf`. Triggers async vector ingestion into ChromaDB. Returns `409 Conflict` if the application has already been reviewed. |
| `GET`  | `/api/agent/health` | Yes (ADMIN) | Health check |

The chat endpoint accepts `{ "message": "..." }`. The agent routes the message to the appropriate tool automatically. The response includes a human-readable `message` from the LLM and a structured `data` payload.

---

## Example Prompts

### Job & Candidate queries

```
"Show me all open jobs"
"Get full details of job 1"
"How many candidates have applied for job 1?"
"Get details of candidate 5"
"Show all candidates who applied for job 1"
"Get the top scored candidates for job 1 with min score 60"
"Get details of application 3"
```

### CV Parsing & Scoring

```
"Parse the CV of candidate 5 for job 1"
"Score candidate 5 for job 1"
"Score all unscored candidates for job 1"
```

### Application Status

```
"Update the status of application 3 to SHORTLISTED"
"Update application 2 to HIRED"
```

### Interview Scheduling

```
"Schedule a technical interview for application 3 on 2025-06-15 10:00 with interviewer Mohammad Abdelhadi"
"List all upcoming scheduled interviews"
"Cancel interview 2, reason: candidate withdrew"
"Get all interviews for candidate 5"
```

### Email Notifications

```
"Send an interview invitation to candidate 5 for interview 1"
"Send a rejection email for application 3"
"Send a job offer for application 3 with salary 25000 AED starting July 1st"
```

### Job Posting Management

```
"Create a new Backend Engineer job in Engineering, requires Java and Spring Boot, 3 years experience, Dubai, salary 10000 to 15000"
"Update job 3 to require 5 years experience and add Kubernetes to required skills"
"Close job posting 5"
"Mark job 2 as filled"
"Search for DevOps jobs"
"Show me all Engineering department jobs"
"Give me a summary of all job postings"
"Delete job posting 7"
```

---

## Running Tests

```bash
mvn test
```

55 unit tests covering all tool classes:

| Test Class | Tests |
|---|---|
| `CandidateToolTest` | 12 |
| `JobPostingToolTest` | 16 |
| `SchedulerToolTest` | 9 |
| `CvParserToolTest` | 6 |
| `EmailToolTest` | 5 |
| `ScoringToolTest` | 6 |
| `HrAgentApplicationTests` | 1 |
| **Total** | **55** |

Tests use Mockito for all dependencies and real PDFBox for PDF generation — no running database, Ollama, or ChromaDB instance required.

---

## Project Structure

```
src/main/java/com/hr/agent/
├── config/
│   ├── OllamaConfig.java        — OllamaChatModel + HrAgentService (AiServices) beans
│   └── RagConfig.java           — OllamaEmbeddingModel, ChromaEmbeddingStore, EmbeddingStoreIngestor beans
├── controller/
│   ├── AgentController.java     — chat, CV upload (publishes CvUploadedEvent), health
│   └── AuthController.java
├── dto/
│   ├── auth/                    LoginRequest, RegisterRequest, AuthResponse,
│   │                            ChangePasswordRequest, ForgotPasswordRequest, ResetPasswordRequest
│   └── ...                      ChatRequest, ChatResponse, CandidateProfile, ScoringResult
├── entity/                      Candidate, Application, JobPosting, Interview,
│                                AppUser, Role, UserRole, UserRoleId, PasswordResetToken
├── exception/                   GlobalExceptionHandler, DuplicateResourceException, InvalidTokenException
├── rag/
│   ├── CvVectorStore.java       — port interface (index / search CV embeddings)
│   ├── CvChunk.java             — value object: section, content, indexes, BM25 keywords
│   ├── CvSection.java           — enum of CV sections (EXPERIENCE, SKILLS, EDUCATION, …)
│   ├── CvSearchResult.java      — retrieval result (appRefNo, candidateName, section, score)
│   ├── bm25/
│   │   └── BM25Scorer.java      — tokenizer + BM25 scoring + top-N keyword extraction
│   ├── chunking/
│   │   └── CvSectionSplitter.java — section detection, inline-header normalization,
│   │                                oversized-section sub-splitting (no trailing overlap chunk)
│   ├── chroma/
│   │   └── ChromaCvVectorStore.java  — Chroma adapter: section-based indexing + hybrid
│   │                                   dense/BM25 RRF search
│   ├── event/
│   │   └── CvUploadedEvent.java — Spring application event (carries applicationId)
│   └── listener/
│       └── CvIngestionListener.java  — @Async @EventListener: PDF extract → chunk → embed → store
├── repository/                  CandidateRepository, ApplicationRepository, JobPostingRepository,
│                                InterviewRepository, AppUserRepository, RoleRepository,
│                                PasswordResetTokenRepository
├── security/                    SecurityConfig, JwtAuthFilter, JwtUtil,
│                                JwtAuthEntryPoint, AppUserDetailsService
├── service/
│   ├── HrAgentService.java      — LangChain4j AiService interface (chat)
│   ├── AuthService.java
│   ├── PasswordService.java
│   └── PdfTextExtractor.java    — shared PDF → String utility (PDFBox)
└── tools/
    ├── CandidateTool.java
    ├── CvParserTool.java
    ├── EmailTool.java
    ├── JobPostingTool.java
    ├── SchedulerTool.java
    ├── ScoringTool.java
    └── ToolResultContext.java

src/main/resources/
├── application.yml
└── db/changelog/
    ├── db.changelog-master.xml
    └── changes/
        ├── v2.0.0-create-sequences.xml
        ├── v2.0.1-create-job-posting.xml
        ├── v2.0.2-create-candidate.xml
        ├── v2.0.3-create-application.xml
        ├── v2.0.4-create-interview.xml
        ├── v2.0.5-create-auth.xml
        ├── v2.0.6-seed-data.xml
        └── v2.0.7-application-cv-snapshot.xml
```
