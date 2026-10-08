import Base

# Shared registry export supplies these values. No block names determine solid,
# fluid or leaf behavior in the encoder: Minecraft's six predicates supply mask.
type Property is Data:
  Property{name: String, value: String}

type State is Data:
  State{id: String, properties: List<&2, Property>}

type Material is Data:
  Material{state: State, height_mask: U32}
