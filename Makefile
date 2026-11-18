.PHONY: status test-server android-check android-release
status:
	./scripts/remodexctl status
test-server:
	./scripts/test-server.sh
android-check:
	./scripts/build-android.sh :app:testReleaseUnitTest :app:lintVitalRelease
android-release:
	./scripts/release-android.sh
