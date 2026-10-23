# Repeating native latitude bands

The user requested that travel beyond polar climate eventually returns to warm climate,
particularly when using a short polar distance. New profiles now default to the model
`ftf:latitude_v2_repeating_draft`, while native latitude itself remains disabled by default.

The Climate Settings page exposes **Repeat latitude bands**. Turning it off selects the
original `ftf:latitude_v1_draft` behavior. JSON/NBT decoding preserves the saved model;
existing worlds and presets are not automatically migrated. An older preset can explicitly
select repeating bands when configuring a new world.

For equator E and polar distance D, use d=abs(Z-E), p=d modulo 2D, and
u=min(p,2D-p)/D. This folds the existing equator-to-pole curves into a repeating pattern:
warm at E, polar at E±D, warm at E±2D, polar at E±3D, and so on. Both latitude temperature
and moisture use u; their independent strengths, regional noise and existing terrain
shaping retain their meanings. Zero strengths still bypass the latitude arithmetic.
The existing smoothstep targets have zero endpoint slopes, making the reversal smooth.

The model remains part of preset serialization and hence preview identity. Changing the
repeat toggle invalidates and regenerates both previews through the existing editor path.

Validation: `./gradlew :common:latitudeTest :fabric:build :neoforge:build --offline --console=plain`
passed with 231,742 assertions. New checks cover multiple repeating equators/poles,
hemisphere reflection, exact integer-block periodicity near world limits (D=1, 7, 5000,
30000000), bounded targets, seam continuity, both JSON and NBT model preservation,
first-leg equivalence to v1, retained v1 caps and exact zero-strength behavior. Both
remapped loader jars contain the new model and UI strings without the test fixtures.

In-game preview/world acceptance and performance measurements remain pending. Earlier
implementation records describe the original capped model and its original test counts;
this record and the revised SPEC.md describe the repeating extension.
