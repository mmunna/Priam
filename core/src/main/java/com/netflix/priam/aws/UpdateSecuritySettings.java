package com.netflix.priam.aws;

import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.netflix.priam.config.CassandraConfiguration;
import com.netflix.priam.identity.IMembership;
import com.netflix.priam.identity.IPriamInstanceRegistry;
import com.netflix.priam.identity.InstanceIdentity;
import com.netflix.priam.identity.PriamInstance;
import com.netflix.priam.scheduler.SimpleTimer;
import com.netflix.priam.scheduler.Task;
import com.netflix.priam.scheduler.TaskTimer;

import java.util.List;
import java.util.Random;

/**
 * this class will associate an Public IP's with a new instance so they can talk
 * across the regions.
 * <p/>
 * Requirement: 1) Nodes in the same region needs to be able to talk to each
 * other. 2) Nodes in other regions needs to be able to talk to the others in
 * the other region.
 * <p/>
 * Assumption: 1) IPriamInstanceRegistry will provide the membership... and will
 * be visible across the regions 2) IMembership amazon or any other
 * implementation which can tell if the instance is part of the group (ASG in
 * amazons case).
 */
@Singleton
public class UpdateSecuritySettings extends Task {
    public static final String JOBNAME = "Update_SG";
    public static boolean firstTimeUpdated = false;

    private static final Random ran = new Random();
    private final IMembership membership;
    private final IPriamInstanceRegistry instanceRegistry;
    private final CassandraConfiguration cassandraConfiguration;

    @Inject
    public UpdateSecuritySettings(CassandraConfiguration cassandraConfiguration, IMembership membership, IPriamInstanceRegistry instanceRegistry) {
        super();
        this.cassandraConfiguration = cassandraConfiguration;
        this.membership = membership;
        this.instanceRegistry = instanceRegistry;
    }

    /**
     * Seeds nodes execute this at the specifed interval.
     * Other nodes run only on startup.
     * Seeds in cassandra are the first node in each Availablity Zone.
     */
    @Override
    public void execute() {
        // if seed dont execute.
        int port = cassandraConfiguration.getSslStoragePort();
        List<String> acls = membership.listACL(port, port);
        List<PriamInstance> instances = instanceRegistry.getAllIds(cassandraConfiguration.getClusterName());

        // iterate to add...
        List<String> add = Lists.newArrayList();
        for (PriamInstance instance : instanceRegistry.getAllIds(cassandraConfiguration.getClusterName())) {
            String range = instance.getHostIP() + "/32";
            if (!acls.contains(range)) {
                add.add(range);
            }
        }
        if (add.size() > 0) {
            membership.addACL(add, port, port);
            firstTimeUpdated = true;
        }

        // just iterate to generate ranges.
        List<String> currentRanges = Lists.newArrayList();
        for (PriamInstance instance : instances) {
            String range = instance.getHostIP() + "/32";
            currentRanges.add(range);
        }

        // iterate to remove...
        List<String> remove = Lists.newArrayList();
        for (String acl : acls) {
            if (!currentRanges.contains(acl)) // if not found then remove....
            {
                remove.add(acl);
            }
        }
        if (remove.size() > 0) {
            membership.removeACL(remove, port, port);
            firstTimeUpdated = true;
        }
    }

    public static TaskTimer getTimer(InstanceIdentity id) {
        SimpleTimer return_;
        if (id.isSeed()) {
            return_ = new SimpleTimer(JOBNAME, 120 * 1000 + ran.nextInt(120 * 1000));
        } else {
            return_ = new SimpleTimer(JOBNAME);
        }
        return return_;
    }

    @Override
    public String getName() {
        return JOBNAME;
    }
}
