# Specifications

`*.allium` files in this directory are the behavioural specification of
odatoruthall, written in [Allium](https://juxt.github.io/allium): what the
dashboard does, in domain terms, with no implementation detail.

The specification is the primary artefact — the code implements it.

- Read the spec before implementing or changing behaviour.
- Any change to observable behaviour updates the spec **in the same change**
  as the code. A spec that lags behind the code is worse than no spec.
- Refactors and anything invisible to the user leave the spec untouched.
- Run `./scripts/check-specs.sh` after editing. CI runs the same script in
  the `spec-check` job, so a spec that does not check does not merge.
- Open design questions go in the `open question` section of the spec rather
  than in a comment in the code.

The Allium skills come from the `allium` plugin (`juxt-plugins` marketplace,
wired in `.claude/settings.json`) and drive the loop: `/allium` (whole loop),
`/elicit` (spec from intent), `/distill` (spec from existing code),
`/propagate` (tests from the spec), `/tend` (targeted spec edit), `/weed`
(find and fix spec-vs-code drift).

The checker itself is a separate binary: `cargo install allium-cli`.
