# GO BAG Private GitHub Repo Guide

This guide explains how to connect this repository to a private GitHub repo, review changes safely, and avoid pushing local-only files or secrets.

## 1. Before connecting GitHub

Run these checks locally:

```bash
git status
git diff -- .gitignore README.md docs/
```

Review tracked files carefully before the first push. In this repository, the main local-only file found during audit was:

- `android-app/local.properties`

That file contains a machine-specific Android SDK path and should stay ignored and untracked.

## 2. Files that must never be committed

Do not commit:

- real `.env` files
- `gobag.env`
- populated local config copies derived from example files
- `GOBAG_ADMIN_TOKEN` values
- SQLite databases such as `gobag.db`
- Android DataStore files such as `*.preferences_pb`
- logs
- virtual environments
- Android build outputs
- Gradle caches and temp files
- IDE settings and machine-local files such as `android-app/local.properties`

Safe to keep in git:

- `.env.example`
- `pi-server/.env.example`
- `pi-server/config/gobag.env.example`
- documentation that explains how to populate those examples locally

## 3. Check git status before every commit

```bash
git status
```

Look for:

- unexpected local secrets
- generated files
- build outputs
- local DB files
- machine-specific config files

## 4. Review staged changes cleanly

Stage intentionally:

```bash
git add README.md docs/ .gitignore
```

Then review exactly what will be committed:

```bash
git diff --cached
```

If you want file-by-file review:

```bash
git diff --cached -- README.md
git diff --cached -- .gitignore
git diff --cached -- docs/INSTALLATION_AND_DEPLOYMENT.md
```

## 5. Commit cleanly

Example:

```bash
git commit -m "Add install, config, verification, and private repo docs"
```

Good commit habits for this project:

- keep docs/config hygiene separate from feature code when possible
- review `git status` before committing
- avoid mixing local experiments, generated files, and documentation cleanup in one commit

## 6. Add or change the GitHub remote

Check existing remotes:

```bash
git remote -v
```

Set a new origin:

```bash
git remote add origin git@github.com:<your-user>/<your-private-repo>.git
```

If `origin` already exists and you need to change it:

```bash
git remote set-url origin git@github.com:<your-user>/<your-private-repo>.git
```

Verify:

```bash
git remote -v
```

## 7. Push to private GitHub

If this is the first push of your current branch:

```bash
git push -u origin <branch-name>
```

If you are pushing `main`:

```bash
git push -u origin main
```

## 8. Safe example-env workflow

Use this pattern:

1. keep example files in git
2. copy the example on the target machine
3. fill in local values only on that machine
4. never commit the populated copy

For GO BAG, the Pi-side example file to copy is:

- `pi-server/config/gobag.env.example`

Installed runtime config should live outside the repo at:

- `/opt/gobag/config/gobag.env`

## 9. Recommended update workflow for your private repo

Before committing:

```bash
git status
git diff
```

Stage intentionally:

```bash
git add <files-you-actually-want>
```

Review staged changes:

```bash
git diff --cached
```

Commit:

```bash
git commit -m "Describe the change"
```

Push:

```bash
git push
```

## 10. If a local-only file is already tracked

Ignoring a file in `.gitignore` does not remove it from git if it is already tracked.

To stop tracking it while keeping the local file:

```bash
git rm --cached android-app/local.properties
```

Then commit the removal together with the `.gitignore` update.

## 11. Practical pre-push checklist

Before pushing this repo to private GitHub, confirm:

- `.gitignore` covers env files, DBs, logs, build outputs, and local IDE files
- `android-app/local.properties` is ignored and not tracked
- no real `gobag.env` file is in git
- no SQLite database is in git
- no generated token or local secret is in git
- the new docs reflect the sync-first architecture instead of direct Pi CRUD as the main UI model
