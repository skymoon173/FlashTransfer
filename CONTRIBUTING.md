# Contributing

Contributions are welcome.

1. Create a branch from `main`.
2. Keep changes focused and avoid committing build outputs or transferred files.
3. Run the relevant checks before opening a pull request.

```powershell
python -m py_compile server.py
node --check web/app.js
cd android
gradle assembleDebug lintDebug
```

By contributing, you agree that your contribution is licensed under the
project's MIT License.
