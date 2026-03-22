# GitHub Private Repo Setup

This document records what was checked in this repository, what was excluded from version control, and how to publish the project to a private GitHub repository safely.

## What was checked

- Repository root structure and current Git state
- Android app folder contents and generated build outputs
- Raspberry Pi server folder contents and runtime configuration
- Existing top-level docs (`README.md`, `CONTEXT.md`, `docs/`)
- Sensitive file patterns such as environment files, keys, keystores, local properties, and database files
- Nested Git repository risk
- Oversized files that could block the first push

## Repository findings

- The project is now a local Git repository on branch `main`.
- There is currently no Git remote configured.
- No nested `.git` directories were found under subfolders, so there are no accidental submodule/copy-repo issues.
- The largest local-only artifacts are Android heap dumps (`android-app/java_pid*.hprof`) around 780-812 MB each.
- Android build outputs, `.gradle` caches, IDE folders, and local machine configuration are present locally and should stay out of Git.
- No checked-in `.env` file, private key, keystore, Firebase config, or local database dump was found in the workspace scan.
- `android-app/local.properties` contains a machine-specific Android SDK path and must not be committed.

## What is excluded from version control

The repository ignore rules were tightened to keep local and generated files out of Git, including:

- Android build output and Gradle caches
- Android Studio and VS Code workspace files
- heap dumps, APKs, and packaged artifacts
- Python caches and virtual environments
- local SQLite/database files
- local `.env` files
- temporary editor files

See [`.gitignore`](./.gitignore) for the exact rules.

## Secrets and local config handling

- `android-app/local.properties` is ignored because it contains the local SDK path.
- Pi runtime configuration was documented in [`pi-server/.env.example`](./pi-server/.env.example) so local environment values can be recreated without committing machine-specific settings.
- No active secrets were found during the scan, but pairing tokens and SQLite runtime data are generated at runtime and should remain outside version control.

## Ready for private upload?

Yes, with two remaining operator steps:

1. Set your Git commit identity if `user.name` and `user.email` are not configured yet.
2. Create the target GitHub repository as `Private`, then push this local repository to it.

## Standard Git method

If this folder is not initialized yet:

```bash
git init
git branch -M main
```

Configure your commit identity if needed:

```bash
git config user.name "Your Name"
git config user.email "you@example.com"
```

Add and commit:

```bash
git add .
git commit -m "Initial commit"
```

Connect the private GitHub repository and push:

```bash
git remote add origin <REPO_URL>
git push -u origin main
```

If the remote already exists and is wrong:

```bash
git remote set-url origin <REPO_URL>
git push -u origin main
```

## GitHub CLI method

If GitHub CLI is installed and authenticated:

```bash
gh repo create <REPO_NAME> --private --source=. --remote=origin --push
```

If the repo already exists on GitHub:

```bash
gh repo create <REPO_NAME> --private
git remote add origin <REPO_URL>
git add .
git commit -m "Initial commit"
git push -u origin main
```

## Recommended publish flow for this workspace

Use the standard Git remote method unless `gh` is installed on this machine. During inspection, `gh` was not available in the shell, so plain Git is the reliable path here.

## Pre-push checklist

- Confirm the destination repository is marked `Private` on GitHub.
- Confirm `android-app/local.properties` is not staged.
- Confirm no `.env` file, keystore, key, database, or APK is staged.
- Confirm the large `java_pid*.hprof` files are ignored.

## Suggested verification commands

Before pushing:

```bash
git status --short
git remote -v
```

After pushing:

```bash
git branch --show-current
git ls-remote --heads origin
```
