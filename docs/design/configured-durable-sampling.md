# Configured durable sampling (issue #853)

Status: proposed implementation, pending agreement on the
[shared sampling RFC](https://github.com/aws/aws-durable-execution-docs/pull/308).
This changes documented behavior and must not ship as an unannounced patch release.

## Problem

Explicit upstream X-Ray sampling flags override the configured provider sampler for
durable spans. `always_off` can therefore export durable spans while dropping the
ordinary spans from the same provider.

## Cause

The plugin resolves upstream and ambient decisions before consulting the provider,
and consults the provider at root even when a usable propagated parent exists.
The agent bridge likewise loses that original parent when deferring the decision.

## Options

1. Parse environment variables in the plugin. This duplicates provider validation
   and can disagree with a programmatically configured or agent-customized sampler.
2. Add exceptions for individual policies. This retains conflicting authorities and
   cannot faithfully preserve custom sampling results.
3. Remove upstream and ambient short circuits. Evaluate the actual configured
   sampler with the canonical execution trace ID and original propagated parent,
   carrying that same input through deferred resolution.

## Decision

Use option 3. Both views use the original complete remote parent (including its
sampled flag), or root context if that parent is unusable. A valid parent without a
sampled flag is unsampled. Ambient handlers and synthetic execution ancestors are
never inputs to this decision. Every SDK span uses the resulting full sampling
result through the existing scoped wrapper. Existing synthetic-root normalization
and unsupported replacement-sampler boundaries remain explicit.

Span parenting is separate: a known dropped same-trace ambient handler is not used
as the Invocation parent; fall back to the remote execution ancestor or the
materialized synthetic root. This is exporter-level behavior, not X-Ray console
qualification.

The provider owns environment resolution, defaults, validation and lifecycle.
The plugin neither reparses environment variables nor persists sampling decisions.
Stable policy/configuration and original context are required across retries and
reinvocations; the bounded process cache does not support nondeterministic policies.

## Removed behavior and migration

Remove unconditional upstream and ambient sampling precedence and the preliminary
root-policy probe for plain replacement samplers. Those providers keep their
normal per-span behavior with the original remote-parent flags. Applications that
want upstream inheritance use `parentbased_*`; direct `always_on`, `always_off`
and `traceidratio` retain their OpenTelemetry semantics. Java autoconfiguration's
unset default remains `parentbased_always_on`.

Coordinate release with the shared RFC, JavaScript
[#1173](https://github.com/aws/aws-durable-execution-sdk-js/pull/1173), Python
[#887](https://github.com/aws/aws-durable-execution-sdk-python/issues/887), and the
conformance sampling contract. No release version is assigned by this proposal.
