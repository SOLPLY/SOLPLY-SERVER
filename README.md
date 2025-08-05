
<img width="1645" height="586" alt="서버용 배너만들어볼게여" src="https://github.com/user-attachments/assets/f356e4f0-c113-45a5-85d6-717f315474e9" />

# 🍀솔플리
> 혼자보내는 여가가 일상이 된 지금, 나만의 방식과 취향에 맞는 시간을 보내고 싶은 사람들을 위한 장소 및 코스 큐레이션 서비스

## 📌 Swagger API Docs

> http://54.180.170.116:8080/swagger-ui/index.html#/


## 🚀 Tech Stack
| 기술 / 도구        | 내용                              |
|----------------|---------------------------------|
| **Language**   | Java 21                         |
| **Framework**  | Spring Boot 3.3.5               |
| **Build Tool** | Gradle                          |
| **Database**   | Postgresql (RDS), Redis         |
| **Deployment** | AWS EC2, GitHub Actions, Docker |
| **Docs**       | Notion, Swagger UI              |
| **Auth**       | Spring Security + JWT           |
| **CI/CD**      | GitHub Actions + Docker Compose |


## 📂 Project Structure (25.08.05)

```bash
📁 solply-server/
├── 📁 .github
│   └── 📁 workflows
├── 📁 docker
│   ├── 📄 docker-compose.yml
│   ├── 📄 docker-compose.dev.yml
│   ├── 📄 docker-compose.prod.yml
│   └── 📄 Dockerfile
├── 📁 scripts
│   ├── 📄 deploy.sh
├── 📁 src
│   ├── 📁 main
│   │   ├── 📁 java/ 📁 org/ 📁 sopt/ 📁 solply_server
│   │   │   ├── 📁 domain
│   │   │   │   ├── 📁 auth
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
├── 📄 gradle.dev.properties
├── 📄 radle.prod.properties
└── 📄 README.md
```


## 👥 Developers
| ![Image](https://github.com/user-attachments/assets/1d02828d-3ae0-41e9-b6ea-1c7ce6b53f1c) | ![Image](https://github.com/user-attachments/assets/3a80d313-cc09-487a-ba07-1f893e1f5c6d) | ![Image](https://github.com/user-attachments/assets/bf85598e-4951-4640-ad10-fcbf601c50f0) | 
| :-------------: | :----------: | :----------: |
| [신민규](https://github.com/uykm) | [배영경](https://github.com/bykbyk0401) | [이지수](https://github.com/leejisoo0617) |