# HR Recruitment Agent

An AI-powered HR recruitment assistant built with **Spring Boot 3**, **LangChain4j**, **Ollama (llama3.2)**, and **Oracle 19c**. The agent handles the full recruitment lifecycle — from parsing CVs and scoring candidates to scheduling interviews and sending emails — through a natural-language chat interface.

---

## Features

| Capability | Description |
|---|---|
| **JWT Authentication** | Stateless auth with signed JWTs (HS256, 24 h expiry); all agent endpoints require `Authorization: Bearer <token>` |
| **Role-Based Access Control** | Two roles — `ROLE_ADMIN` and `ROLE_RECRUITER`; roles are embedded in the JWT and enforced per-request |
| **Password Management** | Change password (authenticated), forgot/reset password via email token (60-min expiry) |
| **CV Parsing** | Extracts text from PDF CVs using PDFBox, then uses the LLM to parse skills, experience, education, and role |
| **Candidate Scoring** | LLM scores each candidate against job requirements (0–100) and recommends SHORTLIST / CONSIDER / REJECT |
| **Interview Scheduling** | Books interviews with conflict detection, stores date/time/type/interviewer |
| **Email Notifications** | Sends interview invitations, rejection emails, offer letters, and password-reset links via Gmail SMTP |
| **Candidate Management** | Lists jobs, candidates, filters by score threshold, updates statuses |
| **Job Posting Management** | Create, update, close, delete, search, and get stats on job postings via natural language |
| **JSON API** | All tool results returned as structured JSON for easy frontend consumption |

---

## Tech Stack

- **Java 17** / **Spring Boot 3.2.5**
- **Spring Security 6** — stateless JWT filter chain, BCrypt (cost 12)
- **JJWT 0.12** — JWT signing / validation (HS256)
- **LangChain4j 0.36.2** — `AiServices`, `@Tool`, `OllamaChatModel`
- **Ollama** — runs `llama3.2:latest` locally (no cloud API key needed)
- **Oracle 19c** — schema managed by **Liquibase**
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
      ▼
HrAgentService  (LangChain4j AiServices proxy)
      │  tool calls dispatched automatically by the LLM
      ├── CandidateTool      — job/candidate CRUD & queries
      ├── CvParserTool       — PDF → LLM → structured profile
      ├── ScoringTool        — LLM scoring against job requirements
      ├── SchedulerTool      — interview booking & management
      ├── EmailTool          — SMTP notifications
      └── JobPostingTool     — create / update / close / delete / search job postings
             │
             ▼  ThreadLocal capture (ToolResultContext)
AgentController  assembles ChatResponse { message, data }
```

Tool results are captured in a **ThreadLocal** (`ToolResultContext`) before the LLM processes them, so the structured JSON payload reaches the frontend even when the LLM truncates its reply.

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
JOB_POSTING          CANDIDATE                   INTERVIEW
────────────         ──────────────────          ────────────────
id (PK)              id (PK)                     id (PK)
title                full_name                   candidate_id (FK)
department           email                       job_posting_id (FK)
location             phone                       scheduled_at
required_skills      nationality                 interview_type
experience_years     cv_file_path                interviewer_name
description          cv_raw_text (CLOB)          duration_minutes
status               skills                      status
                     experience_years            notes
                     education
                     current_role
                     score
                     status
                     job_posting_id (FK)

APP_USER             APP_ROLE                    USER_ROLE
────────────         ────────────                ────────────────
id (PK)              id (PK)                     user_id (PK, FK)
username (unique)    name (unique)               role_id (PK, FK)
email (unique)       description                 assigned_at
password_hash
full_name
enabled
created_at
updated_at
```

Roles seeded on first startup: `ROLE_ADMIN`, `ROLE_RECRUITER`. New users registered via `/api/auth/register` are automatically assigned `ROLE_RECRUITER`.

---

## Prerequisites

| Requirement | Notes |
|---|---|
| Java 17+ | |
| Maven 3.9+ | or use `./mvnw` |
| Oracle 19c | Schema: `hr_agent` / password: your choice |
| Ollama | Install from [ollama.com](https://ollama.com), then `ollama pull llama3.2` |
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
# Install Ollama, then pull the model
ollama pull llama3.2
ollama serve          # starts on http://localhost:11434
```

### 3. Gmail App Password

1. Enable 2-Step Verification on your Google account
2. Go to **Google Account → Security → App Passwords**
3. Create a password for "Mail"
4. Use the 16-character password in `application-local.yml`

### 4. Configuration

Create `src/main/resources/application-local.yml` (this file is git-ignored):

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
    reset-url: http://localhost:3000/reset-password   # your frontend reset page

security:
  jwt:
    secret: YOUR_SECRET_MIN_32_CHARS   # generate with: openssl rand -hex 32
    expiration: 86400000               # 24 h in milliseconds
```

Alternatively, supply secrets via environment variables — the defaults in `application.yml` read from `$JWT_SECRET`, `$DB_PASSWORD`, `$MAIL_USERNAME`, `$MAIL_PASSWORD`, `$CV_STORAGE_PATH`, and `$PASSWORD_RESET_URL`.

### 5. Run

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The API will be available at `http://localhost:8080`.

---

## API Reference

A ready-to-import Postman collection covering every endpoint is included in the repository:

```
src/main/resources/postman-collections/HR Agent Collection.postman_collection.json
```

You can also open it directly in Postman via the shared link in the collection file (`info._collection_link`).

> All `/api/agent/*` endpoints require `Authorization: Bearer <token>`.  
> Auth endpoints (`/api/auth/login`, `/api/auth/register`, `/api/auth/forgot-password`, `/api/auth/reset-password`) are public.

### Auth endpoints

| Method | Path | Auth required | Description |
|---|---|---|---|
| `POST` | `/api/auth/register` | No | Create a new recruiter account |
| `POST` | `/api/auth/login` | No | Log in and receive a JWT |
| `POST` | `/api/auth/change-password` | Yes | Change password (current password required) |
| `POST` | `/api/auth/forgot-password` | No | Send a password-reset link to the account email |
| `POST` | `/api/auth/reset-password` | No | Reset password using the emailed token (valid 60 min) |

A default admin account is seeded on first startup — username `admin`, password `Admin@1234`.

### Agent endpoints

| Method | Path | Auth required | Description |
|---|---|---|---|
| `POST` | `/api/agent/chat` | Yes | Send a natural-language message to the HR agent |
| `POST` | `/api/agent/upload-cv` | Yes | Upload a PDF CV (multipart: `file`, `jobId`, `name`, `email`, `phone`) |
| `GET`  | `/api/agent/health` | No | Health check |

The chat endpoint accepts a JSON body `{ "message": "..." }`. The `message` field is a natural-language instruction; the agent routes it to the appropriate tool automatically. The response includes both a human-readable `message` from the LLM and a structured `data` payload for the frontend.

---

## Example Prompts

These are the prompts already saved in the Postman collection:

```
"Show me all open jobs"
"Get full details of a job posting for job 1"
"Count how many candidates have applied for a job 1"
"Get details of a single candidate 21"
"Get the top scored candidates for a job 1 min score 50"
"Get candidate 43 score for job 1"
"Parse the CV of a candidate 43"
"Schedule a technical interview for candidate 43 on 2025-06-15 10:00 with interviewer Mohammad Abdelhadi"
"List all upcoming scheduled interviews."
"Send an interview invitation email to a candidate 43 interview Id 1"
```

### Job Posting Management prompts

```
"Create a new Backend Engineer job in Engineering, requires Java and Spring Boot, 3 years experience, Dubai, salary 10000 to 15000"
"Update job 3 to require 5 years experience and add Kubernetes to required skills"
"Close job posting 5"
"Mark job 2 as filled"
"Search for DevOps jobs"
"Show me all Engineering department jobs"
"Show me all open Engineering jobs"
"Give me a summary of all job postings"
"Delete job posting 7"
```

---

## Running Tests

```bash
mvn test
```

40 unit tests covering all tool classes:

| Test Class | Tests |
|---|---|
| `CandidateToolTest` | 12 |
| `SchedulerToolTest` | 9 |
| `EmailToolTest` | 6 |
| `ScoringToolTest` | 6 |
| `CvParserToolTest` | 6 |
| `JobPostingToolTest` | 16 |
| **Total** | **56** |

Tests use Mockito for dependencies and real PDFBox for PDF generation — no running database or Ollama instance required.

---

## Project Structure

```
src/main/java/com/hr/agent/
├── config/          OllamaConfig.java
├── controller/      AgentController.java, AuthController.java
├── dto/
│   ├── auth/        LoginRequest, RegisterRequest, AuthResponse,
│   │                ChangePasswordRequest, ForgotPasswordRequest, ResetPasswordRequest
│   └── ...          ChatRequest, ChatResponse, CandidateProfile, ScoringResult
├── entity/          Candidate, JobPosting, Interview,
│                    AppUser, Role, UserRole, UserRoleId, PasswordResetToken
├── exception/       GlobalExceptionHandler, DuplicateResourceException, InvalidTokenException
├── repository/      CandidateRepository, JobPostingRepository, InterviewRepository,
│                    AppUserRepository, RoleRepository, PasswordResetTokenRepository
├── security/        SecurityConfig, JwtAuthFilter, JwtUtil,
│                    JwtAuthEntryPoint, AppUserDetailsService
├── service/         HrAgentService.java, AuthService.java, PasswordService.java
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
└── db/changelog/    Liquibase migrations (v1.0.0 → v1.0.7)
```
