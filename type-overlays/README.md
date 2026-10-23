# Type overlays

Console hints and drift mappings per resource type, in the shape the platform change P-06 gives
`kind: ResourceType`, kept here until the platform carries them. See the hub README, "Type
overlays".

Phase 0 drafted them (2026-10-23). Each `<type>.yaml` is a `kind: TypeOverlay` file that is merged
over the platform's `resource-types/<type>.yaml` (the copy pinned at `c9529d5` lives in
`contracts/platform/sample-content/`): `metadata.service` becomes the type's service, `console` and
`observe` are P-06's blocks unchanged, and `schema.properties.<field>.x-console` adds hints to the
type's fields. The merged result is the file P-15 adds to the platform, and what `platform-fake`
serves from `GET /api/v1/resource-types`.

| File | What it shows |
| --- | --- |
| `vm.yaml` | The polished example: the full `console` block, `observe` through vSphere's `vm.get` (cpu, memoryGb, powerState), `discover` through `vm.list`, hints on name, cpu, memoryGb and environment, with `mutable` on the two fields P-15's `update-vm` changes. `powerState` is an observed-only field the overlay adds, since vSphere reports it and no workflow sets it |
| `database.yaml` | `observe` through the MongoDB provider's `cluster.get` (version, tier), the secret marked sensitive, a few columns |
| `image.yaml`, `volume.yaml`, `environment.yaml` | Only `metadata.service`, on purpose: the console must draw these types from their schema and workflows alone (P-15) |
| `regions/global.yaml` | The one region, exactly as P-07 shows it |

The services are `compute` (vm, image), `data` (database), `storage` (volume) and `platform`
(environment, which is also what P-06's default would give it).

`npm run check` validates every overlay against `contracts/schemas/type-overlay.schema.json`, the
merged type against `contracts/schemas/resource-type.schema.json`, the region against
`contracts/schemas/region.schema.json`, and checks that `observe.provider` and every action exist
in the pinned provider files with `effect: read`, and that list columns, mapped fields and ignored
fields are fields of the merged schema (or the built-in columns).
