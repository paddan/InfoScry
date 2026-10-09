# 07 - Legacy OCR-only profiles

## Problem
Admin no longer has an "OCR profiles" section. OCR profiles created directly (no
`sourceLlmProfileId`) still exist and still appear as reading methods, but cannot be
edited, disabled or deleted in the UI.

## Decide
- Keep them as they are (usable, unmanaged), or
- offer a one-time conversion: create an LLM profile from each, link the OCR profile
  to it, and then manage them only as LLM profiles, or
- remove them (history pins profile revisions, so they must at least stay readable).

Recommended: conversion, because it keeps history intact and removes the unmanaged
state.

## Scope
`OcrProfileService`, `OcrProfileStore`, an Admin notice listing unmanaged profiles.
Idempotent: running the conversion twice creates nothing new.

## Acceptance
- Test: a legacy profile is converted once, keeps its revisions, and a collection
  that selected it still resolves the same revision.
