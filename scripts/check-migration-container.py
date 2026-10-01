import argparse
import os
import subprocess
from pathlib import Path
from uuid import uuid4


def sql(statement):
    return subprocess.run(
        [
            "docker",
            "compose",
            "exec",
            "-T",
            "postgres",
            "psql",
            "-U",
            "closetos",
            "-d",
            "closetos",
            "-v",
            "ON_ERROR_STOP=1",
            "-At",
            "-c",
            statement,
        ],
        check=True,
        capture_output=True,
        text=True,
        timeout=30,
    ).stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", default="closetos-api:local-runtime")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    os.chdir(root)
    values = dict(
        line.split("=", 1)
        for line in (root / ".env").read_text().splitlines()
        if "=" in line
    )
    database = "migration_check_" + uuid4().hex
    container = "closetos-migration-check-" + uuid4().hex
    environment = {
        **os.environ,
        "DATABASE_URL": "jdbc:postgresql://localhost:5432/" + database,
        "DATABASE_USERNAME": "closetos",
        "DATABASE_PASSWORD": values["DATABASE_PASSWORD"],
        "APPLICATION_DATABASE_PASSWORD": values["APPLICATION_DATABASE_PASSWORD"],
    }
    command = [
        "docker",
        "run",
        "--rm",
        "--name",
        container,
        "--read-only",
        "--network",
        "host",
        "--cpus",
        "1",
        "--memory",
        "1g",
        "--entrypoint",
        "java",
        "-e",
        "DATABASE_URL",
        "-e",
        "DATABASE_USERNAME",
        "-e",
        "DATABASE_PASSWORD",
        "-e",
        "APPLICATION_DATABASE_PASSWORD",
        args.image,
        "-XX:MaxRAMPercentage=75",
        "-Dloader.main=com.closetos.platform.infrastructure.DatabaseMigration",
        "-cp",
        "/app/app.jar",
        "org.springframework.boot.loader.launch.PropertiesLauncher",
    ]
    sql("CREATE DATABASE " + database)
    try:
        for _ in range(2):
            result = subprocess.run(
                command,
                env=environment,
                capture_output=True,
                text=True,
                timeout=60,
                check=False,
            )
            if result.returncode:
                raise RuntimeError(
                    "Packaged migration failed: " + result.stdout + result.stderr
                )
            assert "Started ClosetOsApplication" not in result.stdout
        inspection = [
            "docker",
            "compose",
            "exec",
            "-T",
            "postgres",
            "psql",
            "-U",
            "closetos",
            "-d",
            database,
            "-v",
            "ON_ERROR_STOP=1",
            "-At",
            "-c",
        ]
        result = subprocess.run(
            [
                *inspection,
                "SELECT count(*), max(version) FROM flyway_schema_history WHERE success",
            ],
            check=True,
            capture_output=True,
            text=True,
            timeout=30,
        )
        assert result.stdout.strip() == "17|017", (
            "Packaged schema history is incomplete"
        )
        grants = subprocess.run(
            [
                *inspection,
                (
                    "SET ROLE closetos_app; SELECT count(*) FROM garment; "
                    "SELECT maximum_active FROM workflow_capacity FOR UPDATE; "
                    "SELECT has_table_privilege(current_user,'flyway_schema_history','SELECT'), "
                    "has_table_privilege(current_user,'expensive_action_policy','UPDATE'), "
                    "has_column_privilege(current_user,'workflow_capacity','maximum_active','UPDATE'), "
                    "has_schema_privilege(current_user,'public','CREATE'), "
                    "has_database_privilege(current_user,current_database(),'TEMP');"
                ),
            ],
            check=True,
            capture_output=True,
            text=True,
            timeout=30,
        )
        assert grants.stdout.strip().splitlines() == ["SET", "0", "4", "f|f|f|f|f"], (
            "Packaged migration did not install restricted application grants"
        )
        subprocess.run(
            [
                *inspection,
                "UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version='001'",
            ],
            check=True,
            capture_output=True,
            text=True,
            timeout=30,
        )
        rejected = subprocess.run(
            command,
            env=environment,
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
        assert (
            rejected.returncode != 0
            and "checksum mismatch" in rejected.stdout + rejected.stderr
        )
        print(
            "Migration container passed: 17 migrations, safe replay, restricted runtime grants, changed-checksum refusal; no web startup"
        )
    finally:
        subprocess.run(
            ["docker", "rm", "--force", container],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        sql("DROP DATABASE " + database + " WITH (FORCE)")


if __name__ == "__main__":
    main()
