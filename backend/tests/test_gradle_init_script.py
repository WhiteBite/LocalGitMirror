"""
Тесты генератора init-скрипта gradle.

Проверяем три вещи: корректность регулярки (это граница безопасности),
отсутствие утечки API-ключа в скрипт без ключа, и покрытие всех точек
подключения репозиториев.
"""

import pytest

from app.core.gradle_init_script import (
    SCRIPT_FILE_NAME,
    protected_group_regex,
    render_init_script,
)


class TestProtectedGroupRegex:
    """Регулярка — это fail-closed. Ошибка здесь = dependency confusion."""

    def test_single_group_exact_match(self):
        regex = protected_group_regex(["ru.kryptonite"])
        # Gradle сопоставляет с полным именем, поэтому anchors не нужны.
        assert re.fullmatch(regex, "ru.kryptonite")
        assert re.fullmatch(regex, "ru.kryptonite.build")
        assert re.fullmatch(regex, "ru.kryptonite.foo.bar")

    def test_single_group_rejects_lookalike(self):
        """ru.kryptoniteevil не должен пройти — это и есть атака."""
        regex = protected_group_regex(["ru.kryptonite"])
        assert not re.fullmatch(regex, "ru.kryptoniteevil")
        assert not re.fullmatch(regex, "ru.kryptoniteevil.build")

    def test_single_group_rejects_parent(self):
        """Вышестоящая группа не защищена — это осознанно."""
        regex = protected_group_regex(["ru.kryptonite"])
        assert not re.fullmatch(regex, "ru")
        assert not re.fullmatch(regex, "com.kryptonite")

    def test_multiple_groups(self):
        regex = protected_group_regex(["ru.kryptonite", "com.acme"])
        assert re.fullmatch(regex, "ru.kryptonite")
        assert re.fullmatch(regex, "ru.kryptonite.build")
        assert re.fullmatch(regex, "com.acme")
        assert re.fullmatch(regex, "com.acme.tools")
        assert not re.fullmatch(regex, "ru.kryptoniteevil")
        assert not re.fullmatch(regex, "com.acmeinc")

    def test_empty_groups_raises(self):
        with pytest.raises(ValueError, match="пуст"):
            protected_group_regex([])
        with pytest.raises(ValueError, match="пуст"):
            protected_group_regex(["  ", ""])

    def test_special_chars_escaped(self):
        """Точка в имени группы — литерал, а не метасимвол."""
        regex = protected_group_regex(["ru.kryptonite"])
        # Без экранирования "ru.kryptonite" подошло бы к "ruxkryptonite".
        assert not re.fullmatch(regex, "ruxkryptonite")


class TestRenderInitScript:
    """Скрипт должен содержать все ключевые элементы и не содержать лишнего."""

    def test_contains_projection_path(self):
        script = render_init_script(
            projection_dir="D:/vault/projections/maven2",
            base_url="http://127.0.0.1:8080",
        )
        assert "D:/vault/projections/maven2" in script
        assert "LgmVaultFiles" in script

    def test_contains_m2_url(self):
        script = render_init_script(
            projection_dir="/tmp/proj",
            base_url="http://127.0.0.1:8080",
        )
        assert "http://127.0.0.1:8080/api/deps/m2/" in script
        assert "LgmVaultHttp" in script

    def test_api_key_included_when_given(self):
        script = render_init_script(
            projection_dir="/tmp/proj",
            base_url="http://localhost:8080",
            api_key="secret-token-123",
        )
        assert "secret-token-123" in script
        assert "HttpHeaderCredentials" in script
        assert "X-Session-ID" in script

    def test_api_key_omitted_when_empty(self):
        """Без ключа — без credentials блока. Gradle ругается на пустые."""
        script = render_init_script(
            projection_dir="/tmp/proj",
            base_url="http://localhost:8080",
            api_key="",
        )
        assert "HttpHeaderCredentials" not in script
        assert "credentials" not in script

    def test_protected_groups_in_regex(self):
        script = render_init_script(
            projection_dir="/tmp/proj",
            base_url="http://localhost:8080",
            protected_groups=["ru.kryptonite", "com.acme"],
        )
        # Регулярка должна содержать обе группы.
        assert "ru\\\\.kryptonite" in script
        assert "com\\\\.acme" in script

    def test_fail_closed_documented(self):
        """Комментарий про fail-closed — это не украшение, а документация."""
        script = render_init_script(
            projection_dir="/tmp/proj",
            base_url="http://localhost:8080",
        )
        assert "FAIL-CLOSED" in script
        assert "dependency confusion" in script

    def test_covers_all_repository_hooks(self):
        """Скрипт должен покрывать все точки подключения репозиториев."""
        script = render_init_script(
            projection_dir="/tmp/proj",
            base_url="http://localhost:8080",
        )
        assert "pluginManagement" in script
        assert "dependencyResolutionManagement" in script
        assert "buildscript" in script
        assert "beforeProject" in script
        assert "settingsEvaluated" in script

    def test_gradle_plugin_portal_preserved(self):
        """Объявление репозитория подавляет дефолт — возвращаем его явно."""
        script = render_init_script(
            projection_dir="/tmp/proj",
            base_url="http://localhost:8080",
        )
        assert "gradlePluginPortal" in script
        assert "LgmPluginPortal" in script

    def test_exclude_filter_on_other_repos(self):
        """Все чужие репозитории получают exclude — это и есть fail-closed."""
        script = render_init_script(
            projection_dir="/tmp/proj",
            base_url="http://localhost:8080",
        )
        assert "excludeGroupByRegex" in script
        assert "repos.all" in script

    def test_windows_path_normalized(self):
        """Windows-пути с backslash должны стать URI-friendly."""
        script = render_init_script(
            projection_dir="D:\\vault\\projections\\maven2",
            base_url="http://localhost:8080",
        )
        # В Groovy-строке backslash экранируется, но в URI его быть не должно.
        assert "D:/vault/projections/maven2" in script

    def test_script_file_name_constant(self):
        """Имя файла стабильное — его ждёт эндпоинт и CLI."""
        assert SCRIPT_FILE_NAME == "lgm-vault.gradle"


import re  # noqa: E402 — для test_single_group_*
