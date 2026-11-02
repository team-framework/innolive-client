// 모든 하위 프로젝트·모듈에 공통으로 적용할 설정을 두는 최상위 빌드 파일입니다.
plugins {
    alias(libs.plugins.kotlin.compose) apply false
}