package io.github.unlocker.archrag.canonicalmodel.node;

/** Kind of a compute instance; {@link #UNSPECIFIED} is for CMDB stubs of unknown type. */
public enum ComputeKind {
    VIRTUAL_MACHINE, PHYSICAL_SERVER, UNSPECIFIED
}
