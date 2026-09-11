# 웹 배포

`apps/web`은 `main`에 반영된 커밋을 기준으로 독립 배포한다. workflow는
`github.sha`를 checkout한 뒤 다음 archive만 전송한다.

```text
git archive --format=tar $GITHUB_SHA apps/web | gzip -n
```

수신기는 SSH 강제 명령 `deploy-web <40자리 소문자 SHA>`만 허용한다. 다른
명령, 추가 인자, 줄바꿈은 거부한다. 수신한 archive의 Git PAX 전역
`comment`가 명령의 SHA와 같은지, 경로가 `apps/web` 아래인지, symlink·hardlink와
특수 파일이 없는지 확인한 뒤 격리된 release 디렉터리에 푼다.

## 호스트 설정

root가 `/etc/innolive/web-deploy.env`를 만들고 다음 여섯 변수를 설정한다.
값은 저장소에 기록하지 않는다.

```text
INNOLIVE_WEB_DIR
INNOLIVE_WEB_RELEASES_DIR
INNOLIVE_WEB_SITE_URL
INNOLIVE_WEB_COMPOSE_PROJECT
INNOLIVE_WEB_DB_CONTAINER
INNOLIVE_WEB_PROXY_CONTAINER
```

현재 운영 경로와 이름은 각각 `/opt/innolive/web-src`,
`/opt/innolive/web-releases`, 공개 사이트 URL, `innolive-web`,
`innolive-web-db-1`, `innolive-caddy`를 사용한다. 배포 스크립트는
`web-src/.env`, `docker-compose.yml`, `docker-compose.server.yml`,
`docker-compose.gpu.yml`이 이미 존재하는지 확인한다. 이 파일과 Caddy 설정,
DB 데이터 및 DB·proxy 서비스는 수정하지 않는다.

GitHub Actions에는 다음 secret과 repository variable을 설정한다. 문서나
로그에 값은 남기지 않는다.

```text
INNOLIVE_DEPLOY_HOST
INNOLIVE_DEPLOY_USER
INNOLIVE_DEPLOY_PORT
INNOLIVE_DEPLOY_SSH_KEY
INNOLIVE_DEPLOY_KNOWN_HOSTS
INNOLIVE_WEB_SITE_URL
```

root는 수신기와 배포 스크립트를 각각
`/opt/innolive/deploy/receive-innolive-web-deploy.sh`와
`/opt/innolive/deploy/deploy-innolive-web.sh`에 설치한다. 스크립트가 바뀌면
root가 두 파일을 다시 설치하고 권한과 별도 sudoers 규칙을 검토한다. SSH
key는 `deploy-web` 수신기를 forced command로 지정하고 agent forwarding,
port forwarding, X11 forwarding, PTY를 끈다. 기존 Go 배포 key와 스크립트는
그대로 둔다.

## 교체와 확인

배포는 flock으로 직렬화한다. 새 release에 web build context와
`INNOLIVE_WEB_REVISION` build arg, SHA가 포함된 image tag만 담은 Compose
override를 만든다. 기존 web image를 rollback용 tag로 저장한 뒤 다음 두
동작만 수행한다.

```text
docker compose ... build web
docker compose ... up -d --no-deps --no-build web
```

배포 전후 `.env`와 세 Compose 파일의 SHA-256, `innolive-web-db-1` 및
`innolive-caddy`의 container ID를 비교한다. web image에는
`org.opencontainers.image.revision` label이 들어간다. 교체 후
`127.0.0.1:3010/api/version`과 공개 URL의 JSON `revision`이 요청 SHA와
같아야 성공으로 기록한다. 이 확인 전에는 배포 state 파일을 만들지 않는다.

성공한 release는 `<SHA>.<무작위 접미사>` 디렉터리에 저장한다. 같은 커밋을
다시 배포해도 이전 시도가 남아 있어 새 release를 만들 수 있다. 성공한
release의 `deployment-state`에는 SHA, image, source, override만
기록한다. Docker build 원문 로그와 endpoint 응답은 해당 release 디렉터리의
root 전용 파일에 보관하고 Actions 출력에는 단계와 SHA만 표시한다.

확인에 실패하면 배포 스크립트가 저장해 둔 이전 image를 사용해 web만
`up -d --no-deps --no-build web`으로 되돌린다. 수동 rollback이 필요하면
원하는 이전 release의 `docker-compose.web-release.yml`을 세 운영 Compose
파일과 함께 지정하고 `web` service만 실행한다. DB와 proxy service를
명령에 포함하지 않는다.
