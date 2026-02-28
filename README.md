
<img width="1645" height="586" alt="서버용 배너만들어볼게여" src="https://github.com/user-attachments/assets/f356e4f0-c113-45a5-85d6-717f315474e9" />

# 🍀솔플리
> 혼자보내는 여가가 일상이 된 지금, 나만의 방식과 취향에 맞는 시간을 보내고 싶은 사람들을 위한 장소 및 코스 큐레이션 서비스

## 📌 Swagger API Docs

> https://dev.api.solply.store/swagger-ui/index.html#/


## 🚀 Tech Stack
| 기술 / 도구        | 내용                              |
|----------------|---------------------------------|
| **Language**   | Java 21                         |
| **Framework**  | Spring Boot 3.3.5               |
| **Build Tool** | Gradle                          |
| **Database**   | MySQL 8.0, Redis         |
| **Deployment** | AWS EC2, GitHub Actions, Docker |
| **Docs**       | Notion, Swagger UI              |
| **Auth**       | Spring Security + JWT           |
| **CI/CD**      | GitHub Actions + Docker Compose |


## 📂 Project Structure (26.02.28)

```bash
📁 solply-server/
├── 📁 .github
│   └── 📁 workflows
├── 📁 docker
│   ├── 📄 docker-compose.yml
│   ├── 📄 docker-compose.dev.yml
│   ├── 📄 docker-compose.prod.yml
│   └── 📄 Dockerfile
├── 📁 nginx
│   └── 📁 conf.d
│       ├── dev.conf
│       └── prod.conf
│   └── 📁 www
│       └── .gitkeep
├── 📁 scripts
│   └── 📄 deploy.sh
├── 📁 src
│   ├── 📁 main
│   │   ├── 📁 java/ 📁 org/ 📁 sopt/ 📁 solply_server
│   │   │   ├── 📁 domain
│   │   │   │   ├── 📁 admin
│   │   │   │   ├── 📁 auth
│   │   │   │   ├── 📁 bookmark
│   │   │   │   └── 📁 course
│   │   │   │       ├── 📁 controller
│   │   │   │       ├── 📁 dto
│   │   │   │       ├── 📁 entity
│   │   │   │       ├── 📁 repository
│   │   │   │       ├── 📁 service
│   │   │   │       └── 📁 util
│   │   │   │   ├── 📁 place
│   │   │   │   ├── 📁 recommend
│   │   │   │   ├── 📁 tag
│   │   │   │   ├── 📁 town
│   │   │   │   └── 📁 user
│   │   │   └── 📁 global
│   │   └── 📁 resources
│   └── 📁 test
├── 📄 build.gradle
├── 📄 gradle.properties
├── 📄 gradle.dev.properties
├── 📄 gradle.prod.properties
└── 📄 README.md
```
