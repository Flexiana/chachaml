# Changelog

All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- M0 project skeleton: dual build (deps.edn + project.clj), Babashka task
  runner (`bb.edn`), `tools.build` entry points (`build.clj`).
- Quality tooling: `clj-kondo` config (warning-level fail), `cljfmt`
  config, kaocha runner (`tests.edn`), `cloverage` 85% line gate.
- Namespace skeletons for `chachaml.{core,context,env,serialize,
  registry,tracking,repl,schema}` and `chachaml.store.{protocol,sqlite}`.
- Baseline load test asserting every namespace compiles.
- GitHub Actions CI: lint + format + 4-job test matrix
  (`{deps,lein} × JDK {17,21}`) + coverage gate.
- Process artifacts: `CONTRIBUTING.md`, `LICENSE` (MIT),
  `.github/PULL_REQUEST_TEMPLATE.md`, ADRs 0001–0004.
