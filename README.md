# HR Recruitment Agent

An AI-powered HR recruitment assistant built with **Spring Boot 3**, **LangChain4j**, **Ollama (llama3.2)**, and **Oracle 19c**. The agent handles the full recruitment lifecycle — from parsing CVs and scoring candidates to scheduling interviews and sending emails — through a natural-language chat interface.

---

## Features

| Capability | Description |
|---|---|
| **CV Parsing** | Extracts text from PDF CVs using PDFBox, then uses the LLM to parse skills, experience, education, and role |
| **Candidate Scoring** | LLM scores each candidate against job requirements (0–100) and recommends SHORTLIST / CONSIDER / REJECT |
| **Interview Scheduling** | Books interviews with conflict detection, stores date/time/type/interviewer |
| **Email Notifications** | Sends interview invitations, rejection emails, and offer letters via Gmail SMTP |
| **Candidate Management** | Lists jobs, candidates, filters by score threshold, updates statuses |
| **JSON API** | All tool results returned as structured JSON for easy frontend consumption |

---

## Tech Stack

- **Java 17** / **Spring Boot 3.2.5**
- **LangChain4j 0.36.2** — `AiServices`, `@Tool`, `OllamaChatModel`
- **Ollama** — runs `llama3.2:latest` locally (no cloud API key needed)
- **Oracle 19c** — schema managed by **Liquibase**
- **PDFBox 3** — PDF text extraction
- **Spring Mail** — Gmail SMTP
- **Lombok**, **Jackson**, **JUnit 5 + Mockito**

---

## Architecture

```
React Frontend
      │  POST /api/agent/chat  (ChatRequest)
      ▼
AgentController
      │  calls HrAgentService
      ▼
HrAgentService  (LangChain4j AiServices proxy)
      │  tool calls dispatched automatically by the LLM
      ├── CandidateTool   — job/candidate CRUD & queries
      ├── CvParserTool    — PDF → LLM → structured profile
      ├── ScoringTool     — LLM scoring against job requirements
      ├── SchedulerTool   — interview booking & management
      └── EmailTool       — SMTP notifications
             │
             ▼  ThreadLocal capture (ToolResultContext)
AgentController  assembles ChatResponse { message, data }
```

Tool results are captured in a **ThreadLocal** (`ToolResultContext`) before the LLM processes them, so the structured JSON payload reaches the frontend even when the LLM truncates its reply.

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
```

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
```

### 5. Run

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The API will be available at `http://localhost:8080`.

---

## API Reference

### Chat with the Agent

```http
POST /api/agent/chat
Content-Type: application/json

{
  "recruiterId": "recruiter-1",
  "message": "Show me all open job positions"
}
```

**Response:**

```json
{
  "recruiterId": "recruiter-1",
  "message": "There are 3 open positions: Java Developer, DevOps Engineer, and Product Manager.",
  "data": {
    "type": "job_list",
    "count": 3,
    "jobs": [...]
  },
  "timestamp": "2026-05-24T10:00:00",
  "success": true
}
```

The `message` field is a human-readable summary from the LLM. The `data` field contains the raw structured payload for the frontend to render.

### Upload a CV

```http
POST /api/agent/upload-cv/{candidateId}
Content-Type: multipart/form-data

file: <PDF file>
```

### Health Check

```http
GET /api/agent/health
```

---

## Example Prompts

```
"List all open job positions"
"Score candidate 3 against job posting 1"
"Parse the CV for candidate 5"
"Schedule a technical interview for candidate 2 for job 1 on 2026-06-15 10:00 with interviewer John Smith"
"Send a rejection email to candidate 4"
"Send an offer to candidate 7 with salary 25,000 AED starting July 1st"
"Show me upcoming interviews"
"What are the top candidates for the Java Developer role?"
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
| **Total** | **40** |

Tests use Mockito for dependencies and real PDFBox for PDF generation — no running database or Ollama instance required.

---

## Project Structure

```
src/main/java/com/hr/agent/
├── config/          OllamaConfig.java
├── controller/      AgentController.java
├── dto/             ChatRequest, ChatResponse, CandidateProfile, ScoringResult
├── entity/          Candidate, JobPosting, Interview
├── repository/      CandidateRepository, JobPostingRepository, InterviewRepository
├── service/         HrAgentService.java
└── tools/
    ├── CandidateTool.java
    ├── CvParserTool.java
    ├── EmailTool.java
    ├── SchedulerTool.java
    ├── ScoringTool.java
    └── ToolResultContext.java

src/main/resources/
├── application.yml
└── db/changelog/    Liquibase migrations (v1.0.0 → v1.0.5)
```

---

## License

MIT
