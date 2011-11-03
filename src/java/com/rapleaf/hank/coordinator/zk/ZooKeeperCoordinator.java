/**
 *  Copyright 2011 Rapleaf
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.rapleaf.hank.coordinator.zk;

import com.rapleaf.hank.coordinator.*;
import com.rapleaf.hank.zookeeper.ZkPath;
import com.rapleaf.hank.zookeeper.ZooKeeperConnection;
import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.util.*;

/**
 * An implementation of the Coordinator built on top of the Apache ZooKeeper
 * service. The ZooKeeperCoordinator initially loads all the configuration into
 * local memory for fast reads. It places watches on nodes in the ZooKeeper
 * service so that it is updated when data is changed, so that it can update its
 * local cache and also notify any listeners that are listening on the data.
 * Currently responds to changes in version number for domains and domain
 * groups, as well as the addition or removal of rings. However, the current
 * implementation of ZooKeeperCoordinator will not respond to addition or
 * removal of domains, domain groups, ring groups, or hosts.
 */
public class ZooKeeperCoordinator extends ZooKeeperConnection implements Coordinator, DomainGroupChangeListener, RingGroupChangeListener {
  private static final String KEY_DOMAIN_ID_COUNTER = ".domain_id_counter";
  private static final Logger LOG = Logger.getLogger(ZooKeeperCoordinator.class);

  /**
   * Used to instantiate a ZooKeeperCoordinator generically.
   */
  public static final class Factory implements CoordinatorFactory {
    private static final String RING_GROUPS_ROOT_KEY = "ring_groups_root";
    private static final String DOMAIN_GROUPS_ROOT_KEY = "domain_groups_root";
    private static final String DOMAINS_ROOT_KEY = "domains_root";
    private static final String SESSION_TIMEOUT_KEY = "session_timeout";
    private static final String CONNECT_STRING_KEY = "connect_string";
    private static final List<String> REQUIRED_KEYS = Arrays.asList(RING_GROUPS_ROOT_KEY, DOMAIN_GROUPS_ROOT_KEY, DOMAINS_ROOT_KEY, SESSION_TIMEOUT_KEY, CONNECT_STRING_KEY);

    public static Map<String, Object> requiredOptions(String zkConnectString, int sessionTimeoutMs, String domainsRoot, String domainGroupsRoot, String ringGroupsRoot) {
      Map<String, Object> opts = new HashMap<String, Object>();
      opts.put(CONNECT_STRING_KEY, zkConnectString);
      opts.put(SESSION_TIMEOUT_KEY, sessionTimeoutMs);
      opts.put(DOMAINS_ROOT_KEY, domainsRoot);
      opts.put(DOMAIN_GROUPS_ROOT_KEY, domainGroupsRoot);
      opts.put(RING_GROUPS_ROOT_KEY, ringGroupsRoot);
      return opts;
    }

    @Override
    public Coordinator getCoordinator(Map<String, Object> options) {
      validateOptions(options);
      try {
        return new ZooKeeperCoordinator((String) options.get(CONNECT_STRING_KEY), (Integer) options.get(SESSION_TIMEOUT_KEY), (String) options.get(DOMAINS_ROOT_KEY), (String) options.get(DOMAIN_GROUPS_ROOT_KEY), (String) options.get(RING_GROUPS_ROOT_KEY));
      } catch (Exception e) {
        throw new RuntimeException("Couldn't make a ZooKeeperCoordinator from options "
            + options, e);
      }
    }

    private void validateOptions(Map<String, Object> options) {
      Set<String> missingKeys = new HashSet<String>();
      for (String requiredKey : REQUIRED_KEYS) {
        if (!options.containsKey(requiredKey)) {
          missingKeys.add(requiredKey);
        }
      }
      if (!missingKeys.isEmpty()) {
        throw new RuntimeException("Options for ZooKeeperCoordinator was missing required keys: "
            + missingKeys);
      }
    }
  }

  private final class WatchForNewDomainGroups extends HankWatcher {
    public WatchForNewDomainGroups()
        throws KeeperException, InterruptedException {
      super();
    }

    @Override
    public void setWatch() throws KeeperException, InterruptedException {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Registering watch on " + domainGroupsRoot);
      }
      zk.getChildren(domainGroupsRoot, this);
    }

    @Override
    public void realProcess(WatchedEvent event) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(getClass().getSimpleName() + " received notification! " + event);
      }
      switch (event.getType()) {
        case NodeChildrenChanged:
          // reload domain groups
          try {
            loadAllDomainGroups();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          break;
        default:
          if (LOG.isDebugEnabled()) {
            LOG.debug("Skipped message with event type: " + event.getType());
          }
      }
    }
  }

  /**
   * We save our watchers so that we can reregister them in case of session
   * expiry.
   */
  private Set<HankWatcher> myWatchers = new HashSet<HankWatcher>();
  private boolean isSessionExpired = false;

  private final Map<String, ZkDomain> domainsByName = new HashMap<String, ZkDomain>();

  private final Map<String, ZkDomainGroup> domainGroups = new HashMap<String, ZkDomainGroup>();
  private final Map<String, ZkRingGroup> ringGroups = new HashMap<String, ZkRingGroup>();

  private final String domainsRoot;
  private final String domainGroupsRoot;
  private final String ringGroupsRoot;
  private WatchForNewDomainGroups watchForNewDomainGroups;

  /**
   * Blocks until the connection to the ZooKeeper service has been established.
   * See {@link ZooKeeperConnection#ZooKeeperConnection(String, int)}
   * <p/>
   * Package-private constructor that is mainly used for testing. The last
   * boolean flag allows you to prevent the ZooKeeperCoordinator from
   * immediately trying to cache all the configuration information from the
   * ZooKeeper service, which is useful if you don't want to have to setup your
   * entire configuration just to run a few simple tests.
   *
   * @param zkConnectString  comma separated host:port pairs, each corresponding to a ZooKeeper
   *                         server. e.g. "127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002"
   * @param sessionTimeoutMs session timeout in milliseconds
   * @param domainsRoot
   * @param domainGroupsRoot
   * @param ringGroupsRoot
   * @throws InterruptedException
   * @throws KeeperException
   * @throws IOException
   */
  ZooKeeperCoordinator(String zkConnectString,
                       int sessionTimeoutMs,
                       String domainsRoot,
                       String domainGroupsRoot,
                       String ringGroupsRoot)
      throws InterruptedException, KeeperException, IOException {
    super(zkConnectString, sessionTimeoutMs);
    this.domainsRoot = domainsRoot;
    this.domainGroupsRoot = domainGroupsRoot;
    this.ringGroupsRoot = ringGroupsRoot;

    LOG.info("Loading metadata for coordinator " + this);
    loadAllDomains();
    loadAllDomainGroups();
    loadAllRingGroups();
    LOG.info("Done loading metadata for coordinator " + this);
    watchForNewDomainGroups = new WatchForNewDomainGroups();
    myWatchers.add(watchForNewDomainGroups);
  }

  @Override
  protected void onConnect() {
    // if the session expired, then we need to reregister all of our
    // StateChangeListeners
    if (isSessionExpired) {
      for (HankWatcher watcher : myWatchers) {
        try {
          watcher.setWatch();
        } catch (Exception e) {
          LOG.error("Unable to reset watch " + watcher + " due to exception!", e);
        }
      }
      isSessionExpired = false;
    }
  }

  @Override
  protected void onSessionExpire() {
    isSessionExpired = true;
  }

  public Domain getDomain(String domainName) {
    return domainsByName.get(domainName);
  }

  @Override
  public Set<DomainGroupVersion> getDomainGroupVersionsForDomain(Domain domain) throws IOException {
    Set<DomainGroupVersion> domainGroupVersions = new HashSet<DomainGroupVersion>();
    for (DomainGroup dg : domainGroups.values()) {
      for (DomainGroupVersion dgv : dg.getVersions()) {
        DomainGroupVersionDomainVersion dgvdv = dgv.getDomainVersion(domain);
        if (dgvdv != null) {
          domainGroupVersions.add(dgv);
        }
      }
    }
    return domainGroupVersions;
  }

  public DomainGroup getDomainGroup(String domainGroupName) {
    return domainGroups.get(domainGroupName);
  }

  public RingGroup getRingGroup(String ringGroupName) {
    return ringGroups.get(ringGroupName);
  }

  /**
   * Completely reloads the config information stored in ZooKeeper into memory.
   * Discards all existing config information.
   *
   * @throws InterruptedException
   * @throws KeeperException
   */
  private void loadAllDomains() throws InterruptedException, KeeperException {
    List<String> domainNames = zk.getChildrenNotHidden(domainsRoot, false);
    for (String domainName : domainNames) {
      LOG.info("Loading metadata for Domain " + domainName);
      domainsByName.put(domainName, new ZkDomain(zk, ZkPath.append(domainsRoot, domainName)));
    }
  }

  private void loadAllDomainGroups() throws InterruptedException, KeeperException, IOException {
    List<String> domainGroupNameList = zk.getChildren(domainGroupsRoot, false);
    synchronized (domainGroups) {
      for (String domainGroupName : domainGroupNameList) {
        LOG.info("Loading metadata for Domain Group " + domainGroupName);
        String dgPath = ZkPath.append(domainGroupsRoot, domainGroupName);
        boolean isComplete = ZkDomainGroup.isComplete(zk, dgPath);
        if (isComplete) {
          domainGroups.put(domainGroupName, new ZkDomainGroup(zk, dgPath, this));
        } else {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Not opening domain group " + dgPath
                + " because it was incomplete.");
          }
        }
      }
    }
  }

  private void loadAllRingGroups() throws InterruptedException, KeeperException {
    List<String> ringGroupNameList = zk.getChildren(ringGroupsRoot, false);
    for (String ringGroupName : ringGroupNameList) {
      LOG.info("Loading metadata for Ring Group " + ringGroupName);
      String ringGroupPath = ZkPath.append(ringGroupsRoot, ringGroupName);
      ZkDomainGroup dgc = domainGroups.get(new String(zk.getData(ringGroupPath, false, null)));
      ringGroups.put(ringGroupName, new ZkRingGroup(zk, ringGroupPath, dgc, this));
    }
  }

  public Set<Domain> getDomains() {
    return new HashSet<Domain>(domainsByName.values());
  }

  public Set<DomainGroup> getDomainGroups() {
    synchronized (domainGroups) {
      return new HashSet<DomainGroup>(domainGroups.values());
    }
  }

  public Set<RingGroup> getRingGroups() {
    return new HashSet<RingGroup>(ringGroups.values());
  }

  @Override
  public Set<RingGroup> getRingGroupsForDomainGroup(DomainGroup domainGroup) {
    String domainGroupName = domainGroup.getName();
    Set<RingGroup> groups = new HashSet<RingGroup>();
    for (RingGroup group : ringGroups.values()) {
      if (group.getDomainGroup().getName().equals(domainGroupName)) {
        groups.add(group);
      }
    }
    return groups;
  }

  public void onDomainGroupChange(DomainGroup newDomainGroup) {
    domainGroups.put(newDomainGroup.getName(), (ZkDomainGroup) newDomainGroup);
  }

  public void onRingGroupChange(RingGroup newRingGroup) {
  }

  public Domain addDomain(String domainName, int numParts, String storageEngineFactoryName, String storageEngineOptions, String partitionerName) throws IOException {
    try {
      ZkDomain domain = (ZkDomain) ZkDomain.create(zk, domainsRoot, domainName, numParts, storageEngineFactoryName, storageEngineOptions, partitionerName, getNextDomainId());
      domainsByName.put(domainName, domain);
      return domain;
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  private int getNextDomainId() throws KeeperException, InterruptedException {
    final String domainIdCounterPath = ZkPath.append(domainsRoot, KEY_DOMAIN_ID_COUNTER);
    if (zk.exists(domainIdCounterPath, false) == null) {
      zk.create(domainIdCounterPath, Integer.toString(1).getBytes());
      return 1;
    }
    while (true) {
      final Stat stat = new Stat();
      final byte[] data = zk.getData(domainIdCounterPath, false, stat);
      int lastVersionNumber = Integer.parseInt(new String(data));
      try {
        lastVersionNumber++;
        zk.setData(domainIdCounterPath, Integer.toString(lastVersionNumber).getBytes(), stat.getVersion());
        return lastVersionNumber;
      } catch (KeeperException.BadVersionException e) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Tried to set the domain id counter to " + lastVersionNumber + " but was preempted by another writer. Retrying.");
        }
      }
    }
  }

  public Domain updateDomain(String domainName, int numParts, String storageEngineFactoryName, String storageEngineOptions, String partitionerName) throws IOException {
    ZkDomain domain = (ZkDomain) getDomain(domainName);
    if (domain == null) {
      throw new IOException("Could not get Domain '" + domainName + "' from Coordinator.");
    } else {
      try {
        domainsByName.put(domainName, ZkDomain.update(zk, domainsRoot, domainName, numParts, storageEngineFactoryName, storageEngineOptions, partitionerName));
        return domain;
      } catch (Exception e) {
        throw new IOException(e);
      }
    }
  }

  public DomainGroup addDomainGroup(String name) throws IOException {
    try {
      ZkDomainGroup dgc = ZkDomainGroup.create(zk, domainGroupsRoot, name, this);
      synchronized (domainGroups) {
        domainGroups.put(name, dgc);
      }
      return dgc;
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  public RingGroup addRingGroup(String ringGroupName, String domainGroupName) throws IOException {
    try {
      RingGroup rg = ZkRingGroup.create(zk, ZkPath.append(ringGroupsRoot, ringGroupName),
          (ZkDomainGroup) getDomainGroup(domainGroupName), this);
      ringGroups.put(ringGroupName, (ZkRingGroup) rg);
      return rg;
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  public void close() {
    watchForNewDomainGroups.cancel();
    try {
      zk.close();
    } catch (InterruptedException e) {
      // TODO: uh oh!
      LOG.warn("Interrupted while trying to close ZK connection!", e);
    }
  }

  @Override
  public String toString() {
    return "ZooKeeperCoordinator [quorum=" + getConnectString()
        + ", domainsRoot=" + domainsRoot + ", domainGroupsRoot="
        + domainGroupsRoot + ", ringGroupsRoot=" + ringGroupsRoot + "]";
  }

  public boolean deleteDomain(String domainName) throws IOException {
    ZkDomain domain = domainsByName.remove(domainName);

    if (domain == null) {
      return false;
    }

    // remove domain from all domain group versions
    for (DomainGroup dg : getDomainGroups()) {
      DomainGroups.removeDomainFromAllVersions(dg, domain);
    }

    return domain.delete();
  }

  public boolean deleteDomainGroup(String domainGroupName) throws IOException {
    ZkDomainGroup domainGroup = domainGroups.remove(domainGroupName);
    if (domainGroup == null) {
      return false;
    }
    return domainGroup.delete();
  }

  public boolean deleteRingGroup(String ringGroupName) throws IOException {
    ZkRingGroup ringGroup = ringGroups.remove(ringGroupName);
    if (ringGroup == null) {
      return false;
    }
    return ringGroup.delete();
  }
}
