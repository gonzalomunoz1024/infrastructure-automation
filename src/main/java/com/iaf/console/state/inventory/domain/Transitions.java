package com.iaf.console.state.inventory.domain;

import static com.iaf.console.state.inventory.domain.ResourceState.ACTIVE;
import static com.iaf.console.state.inventory.domain.ResourceState.DELETED;
import static com.iaf.console.state.inventory.domain.ResourceState.DELETING;
import static com.iaf.console.state.inventory.domain.ResourceState.FAILED;
import static com.iaf.console.state.inventory.domain.ResourceState.PENDING;
import static com.iaf.console.state.inventory.domain.ResourceState.UPDATING;

import java.util.Map;

import com.iaf.console.kit.errors.ConflictException;

/**
 * The resource state machine of {@code console-events.yaml}: which event may happen in which
 * state, and the state it leads to. {@code ResourceRequested} opens a resource in pending,
 * {@code ProvisioningStarted} keeps it where it is (and records the job), {@code ProvisioningSucceeded}
 * makes it active, {@code ProvisioningFailed} makes a pending resource failed and ends an update or
 * a delete (the resource is still there, so active), {@code UpdateRequested} and {@code DeleteRequested}
 * need an idle resource (one change at a time), {@code Deleted} is the tombstone, {@code Imported}
 * opens a resource straight in active (or brings a tombstone back), and the rest leave the state alone.
 */
public final class Transitions {

    private Transitions() {
    }

    /** The state after an event in a state; a {@link ConflictException} (a 409) when the event may not happen there. */
    public static ResourceState next(ResourceState current, ResourceEventType event, Resource resource) {
        if (current == null) {
            return switch (event) {
                case RESOURCE_REQUESTED -> PENDING;
                case IMPORTED -> ACTIVE;
                default -> throw new ConflictException("resource.unknown: a resource opens with ResourceRequested or Imported, not " + event.wire());
            };
        }
        if (current == DELETED && event != ResourceEventType.IMPORTED) {
            throw new TombstoneException(resource.tombstone());
        }
        return switch (event) {
            case RESOURCE_REQUESTED -> throw new ConflictException("resource.exists: the resource was already requested");
            case IMPORTED -> {
                if (current == DELETED) {
                    yield ACTIVE;
                }
                throw new ConflictException("resource.exists: the resource is already managed");
            }
            case PROVISIONING_STARTED -> {
                if (current.busy()) {
                    yield current;
                }
                throw new ConflictException("resource.idle: no job is expected on a resource that is " + current.wire());
            }
            case PROVISIONING_SUCCEEDED -> switch (current) {
                case PENDING, UPDATING, ACTIVE, FAILED -> ACTIVE;
                default -> throw new ConflictException("resource.deleting: a deleting resource cannot be provisioned");
            };
            case PROVISIONING_FAILED -> switch (current) {
                case PENDING, FAILED -> FAILED;
                case UPDATING, DELETING -> ACTIVE;
                default -> throw new ConflictException("resource.idle: no job to fail on a resource that is " + current.wire());
            };
            case UPDATE_REQUESTED -> switch (current) {
                case ACTIVE -> UPDATING;
                case PENDING, UPDATING, DELETING -> throw busy(resource, current);
                default -> throw new ConflictException("resource.notActive: only an active resource can be updated, this one is " + current.wire());
            };
            case DELETE_REQUESTED -> switch (current) {
                case ACTIVE, FAILED -> DELETING;
                case DELETING -> throw new ConflictException("resource.deleting: a delete is already under way", detail(resource));
                default -> throw busy(resource, current);
            };
            case DELETED -> DELETED;
            case OBSERVED, DRIFT_DETECTED, DRIFT_RESOLVED, TAGGED -> current;
        };
    }

    private static ConflictException busy(Resource resource, ResourceState current) {
        return new ConflictException("resource.busy: one change at a time, the resource is " + current.wire(), detail(resource));
    }

    private static Map<String, Object> detail(Resource resource) {
        if (resource == null) {
            return Map.of();
        }
        return resource.lastJobId() == null ? Map.of("state", resource.state().wire())
                : Map.of("state", resource.state().wire(), "jobId", resource.lastJobId());
    }
}
