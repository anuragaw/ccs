// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.cloud.containercluster;

import com.cloud.api.ApiDBUtils;
import com.cloud.containercluster.dao.ContainerClusterDao;
import com.cloud.containercluster.dao.ContainerClusterDetailsDao;
import com.cloud.containercluster.dao.ContainerClusterVmMapDao;
import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.DataCenterVO;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.firewall.FirewallService;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.RulesService;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.AccountManager;
import com.cloud.user.SSHKeyPairVO;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.SSHKeyPairDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionCallbackWithException;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.net.Ip;
import com.cloud.vm.Nic;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;
import com.cloud.vm.UserVmService;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.network.Network;
import com.cloud.offering.ServiceOffering;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.utils.component.ManagerBase;
import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.user.containercluster.CreateContainerClusterCmd;
import org.apache.cloudstack.api.command.user.containercluster.DeleteContainerClusterCmd;
import org.apache.cloudstack.api.command.user.containercluster.ListContainerClusterCmd;
import org.apache.cloudstack.api.command.user.firewall.CreateFirewallRuleCmd;
import org.apache.cloudstack.api.command.user.vm.StartVMCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ContainerClusterResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.log4j.Logger;

import javax.ejb.Local;
import javax.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.net.Socket;
import java.security.SecureRandom;
import org.apache.commons.codec.binary.Base64;

@Local(value = {ContainerClusterManager.class})
public class ContainerClusterManagerImpl extends ManagerBase implements ContainerClusterManager, ContainerClusterService {

    private static final Logger s_logger = Logger.getLogger(ContainerClusterManagerImpl.class);

    @Inject
    ContainerClusterDao _containerClusterDao;
    @Inject
    ContainerClusterVmMapDao _clusterVmMapDao;
    @Inject
    ContainerClusterDetailsDao _containerClusterDetailsDao;
    @Inject
    protected SSHKeyPairDao _sshKeyPairDao;
    @Inject
    public UserVmService _userVmService;
    @Inject
    protected DataCenterDao _dcDao = null;
    @Inject
    protected ServiceOfferingDao _offeringDao = null;
    @Inject
    protected VMTemplateDao _templateDao = null;
    @Inject
    protected AccountDao _accountDao = null;
    @Inject
    private UserVmDao _vmDao = null;
    @Inject
    ConfigurationDao _globalConfigDao;
    @Inject
    NetworkService _networkService;
    @Inject
    NetworkOfferingDao _networkOfferingDao;
    @Inject
    protected NetworkModel _networkModel = null;
    @Inject
    PhysicalNetworkDao _physicalNetworkDao;
    @Inject
    protected NetworkOrchestrationService _networkMgr = null;
    @Inject
    protected NetworkDao _networkDao;
    @Inject
    private IPAddressDao _publicIpAddressDao;
    @Inject
    PortForwardingRulesDao _portForwardingDao;
    @Inject
    private FirewallService _firewallService;
    @Inject
    public RulesService _rulesService;
    @Inject
    public NetworkOfferingServiceMapDao _ntwkOfferingServiceMapDao;
    @Inject
    private AccountManager _accountMgr;
    @Inject
    private ContainerClusterVmMapDao _containerClusterVmMapDao;
    @Inject
    private ServiceOfferingDao _srvOfferingDao;
    @Inject
    private UserVmDao _userVmDao;

    static String readFile(String path) throws IOException
    {
        byte[] encoded = Files.readAllBytes(Paths.get(path));
        return new String(encoded);
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<Class<?>>();
        cmdList.add(CreateContainerClusterCmd.class);
        cmdList.add(DeleteContainerClusterCmd.class);
        cmdList.add(ListContainerClusterCmd.class);
        return cmdList;
    }

    @Override
    public ContainerCluster createContainerCluster(final String name,
                                                   final String displayName,
                                                   final DataCenter zone,
                                                   final ServiceOffering serviceOffering,
                                                   final Account owner,
                                                   final Long networkId,
                                                   final String sshKeyPair,
                                                   final Long clusterSize)
            throws InsufficientCapacityException, ResourceAllocationException, ManagementServerException {

        String templateName = _globalConfigDao.getValue(CcsConfig.ContainerClusterTemplateName.key());
        if (templateName == null || templateName.isEmpty()) {
            throw new ManagementServerException("'cloud.container.cluster.template.name' global setting is empty. " +
                    "Admin has not setup the template name to be used for provisioning cluster");
        }

        String masterCloudConfig = _globalConfigDao.getValue(CcsConfig.ContainerClusterMasterCloudConfig.key());
        if (masterCloudConfig == null || masterCloudConfig.isEmpty()) {
            throw new ManagementServerException("'cloud.container.cluster.master.cloudconfig' global setting is empty." +
                    "Admin has not setup the cloud config template to be used for provisioning master");
        }

        String nodeCloudConfig = _globalConfigDao.getValue(CcsConfig.ContainerClusterNodeCloudConfig.key());
        if (nodeCloudConfig == null || nodeCloudConfig.isEmpty()) {
            throw new ManagementServerException("'cloud.container.cluster.node.cloudconfig' global setting is empty. " +
                    "Admin has not setup the cloud config template to be used for provisioning node");
        }

        Network network = null;
        if (networkId != null) {
            network = _networkService.getNetwork(networkId);
            if (network == null) {
                throw new InvalidParameterValueException("Unable to find network by ID " + networkId);
            }
        } else {

            String networkOfferingName = _globalConfigDao.getValue(CcsConfig.ContainerClusterNetworkOffering.key());
            if (networkOfferingName == null || networkOfferingName.isEmpty()) {
                throw new ManagementServerException("'cloud.container.cluster.network.offering' global setting is empty. " +
                        "Admin has not yet specified the network offering to be used for provisioning isolated network for the cluster.");
            }

            NetworkOfferingVO networkOffering = _networkOfferingDao.findByUniqueName(networkOfferingName);
            if (networkOffering == null) {
                throw new ManagementServerException("Network offering with name :" + networkOfferingName + " specified by admin is not found.");
            }

            if (networkOffering.getState() == NetworkOffering.State.Disabled) {
                throw new ManagementServerException("Network offering :" + networkOfferingName + "is not enabled.");
            }

            List<String> services = _ntwkOfferingServiceMapDao.listServicesForNetworkOffering(networkOffering.getId());
            if (services == null || services.isEmpty() || !services.contains("SourceNat")) {
                throw new ManagementServerException("Network offering :" + networkOfferingName + "has no services or does not have source NAT service");
            }

            if (networkOffering.getEgressDefaultPolicy() == false) {
                throw new ManagementServerException("Network offering :" + networkOfferingName + "has egress default policy turned off.");
            }

            long physicalNetworkId = _networkModel.findPhysicalNetworkId(zone.getId(), networkOffering.getTags(), networkOffering.getTrafficType());
            // Validate physical network
            PhysicalNetwork physicalNetwork = _physicalNetworkDao.findById(physicalNetworkId);
            if (physicalNetwork == null) {
                throw new ManagementServerException("Unable to find physical network with id: " + physicalNetworkId + " and tag: "
                        + networkOffering.getTags());
            }

            s_logger.debug("Creating network for account " + owner + " from the network offering id=" +
                    networkOffering.getId() + " as a part of cluster: " + name + " deployment process");

            Network newNetwork = _networkMgr.createGuestNetwork(networkOffering.getId(), name + "-network", owner.getAccountName() + "-network",
                    null, null, null, null, owner, null, physicalNetwork, zone.getId(), ControlledEntity.ACLType.Account, null, null, null, null, true, null);

            if (newNetwork != null) {
                network = _networkDao.findById(newNetwork.getId());
            }
        }

        if(sshKeyPair != null && !sshKeyPair.isEmpty()) {

            SSHKeyPairVO sshkp = _sshKeyPairDao.findByName(owner.getAccountId(), owner.getDomainId(), sshKeyPair);
            if (sshkp == null) {
                throw new InvalidParameterValueException("A key pair with name '" + sshKeyPair + "' was not found.");
            }
        }

        final VMTemplateVO template = _templateDao.findByTemplateName(templateName);
        if (template == null) {
            throw new ManagementServerException("Unable to find the template:" + templateName  +" to be used for provisioning cluster");
        }

        final Network defaultNetwork = network;
        final long cores = serviceOffering.getCpu() * clusterSize;
        final long memory = serviceOffering.getRamSize() * clusterSize;

        ContainerClusterVO cluster = Transaction.execute(new TransactionCallback<ContainerClusterVO>() {

            @Override
            public ContainerClusterVO doInTransaction(TransactionStatus status) {
                ContainerClusterVO newCluster = new ContainerClusterVO(name, displayName, zone.getId(),
                        serviceOffering.getId(), template.getId(), defaultNetwork.getId(), owner.getDomainId(),
                        owner.getAccountId(), clusterSize, "Created", sshKeyPair, cores, memory, "", "");
                _containerClusterDao.persist(newCluster);
                return newCluster;
            }
        });
        return cluster;
    }

    private void updateContainerClusterState(long containerClusterId, String state) {
        ContainerClusterVO containerCluster = _containerClusterDao.findById(containerClusterId);
        containerCluster.setState(state);
        _containerClusterDao.update(containerCluster.getId(), containerCluster);
    }

    public static String getStackTrace(final Throwable throwable) {
        final StringWriter sw = new StringWriter();
        final PrintWriter pw = new PrintWriter(sw, true);
        throwable.printStackTrace(pw);
        return sw.getBuffer().toString();
    }

    @Override
    public ContainerCluster startContainerCluster(long containerClusterId) throws ManagementServerException,
            ResourceAllocationException, ResourceUnavailableException, InsufficientCapacityException {

        ContainerClusterVO containerCluster = _containerClusterDao.findById(containerClusterId);

        s_logger.debug("Provisioning the VM's in the container cluster: " + containerCluster.getName());

        updateContainerClusterState(containerClusterId, "Starting");

        Account account = _accountDao.findById(containerCluster.getAccountId());

        DataCenter dc = _dcDao.findById(containerCluster.getZoneId());
        final DeployDestination dest = new DeployDestination(dc, null, null, null);
        final ReservationContext context = new ReservationContextImpl(null, null, null, account);

        try {
            _networkMgr.startNetwork(containerCluster.getNetworkId(), dest, context);
        } catch(Exception e) {
            updateContainerClusterState(containerClusterId, "Error");
            s_logger.error("Starting the network failed");
            throw new ManagementServerException("Failed to start the network while starting the cluster: " +
                    containerCluster.getName() + " due to : " + e);
        }

        UserVm k8sMasterVM = null;

        try {
            s_logger.debug("Provisioning the master VM's in the container cluster: " + containerCluster.getName());
            k8sMasterVM = createK8SMaster(containerCluster);
        } catch (Exception e) {
            updateContainerClusterState(containerClusterId, "Error");
            s_logger.error("Provisioning the master VM' failed in the container cluster: " + containerCluster.getName());
            throw new ManagementServerException("Provisioning the master VM' failed in the container cluster: "
                    + containerCluster.getName() + " due to " + e);
        }

        final long clusterId = containerCluster.getId();
        final long masterVmId = k8sMasterVM.getId();
        ContainerClusterVmMapVO clusterMasterVmMap = Transaction.execute(new TransactionCallback<ContainerClusterVmMapVO>() {

            @Override
            public ContainerClusterVmMapVO doInTransaction(TransactionStatus status) {
                ContainerClusterVmMapVO newClusterVmMap = new ContainerClusterVmMapVO(clusterId, masterVmId);
                _clusterVmMapDao.persist(newClusterVmMap);
                return newClusterVmMap;
            }
        });

        String masterIP = k8sMasterVM.getPrivateIpAddress();

        IPAddressVO publicIp = null;
        List<IPAddressVO> ips = _publicIpAddressDao.listByAssociatedNetwork(containerCluster.getNetworkId(), true);
        if (ips != null && !ips.isEmpty()) {
            publicIp = ips.get(0);
            containerCluster.setEndpoint("https://" + publicIp.getAddress() + "/");
        }

        long anyNodeVmId = 0;
        UserVm k8anyNodeVM = null;
        s_logger.debug("Provisioning the node VM's in the container cluster: " + containerCluster.getName());
        for (int i=1; i <= containerCluster.getNodeCount(); i++) {
            UserVm vm = null;
            try {
                vm = createK8SNode(containerCluster, masterIP, i);
            } catch (Exception e) {
                updateContainerClusterState(containerClusterId, "Error");
                s_logger.error("Provisioning the node VM failed in the container cluster: " + containerCluster.getName());
                throw new ManagementServerException("Provisioning the node VM failed in the container cluster: "
                        + containerCluster.getName() + " due to " + e);
            }

            if (anyNodeVmId == 0) {
                anyNodeVmId = vm.getId();
                k8anyNodeVM = vm;
            }

            final long nodeVmId = vm.getId();
            ContainerClusterVmMapVO clusterNodeVmMap = Transaction.execute(new TransactionCallback<ContainerClusterVmMapVO>() {

                @Override
                public ContainerClusterVmMapVO doInTransaction(TransactionStatus status) {
                    ContainerClusterVmMapVO newClusterVmMap = new ContainerClusterVmMapVO(clusterId, nodeVmId);
                    _clusterVmMapDao.persist(newClusterVmMap);
                    return newClusterVmMap;
                }
            });
        }

        _containerClusterDao.update(containerCluster.getId(), containerCluster);
        s_logger.debug("Container cluster : " + containerCluster.getName() + " VM's are successfully provisioned.");

        int retryCounter = 0;
        int maxRetries = 10;
        boolean clusterSetup = false;

        List<String> sourceCidrList = new ArrayList<String>();
        sourceCidrList.add("0.0.0.0/0");

        try {

            s_logger.debug("Provisioning firewall rule to open up port 443 on " + publicIp.getAddress() + " for cluster.");

            CreateFirewallRuleCmd rule = new CreateFirewallRuleCmd();
            rule = ComponentContext.inject(rule);

            Field addressField = rule.getClass().getDeclaredField("ipAddressId");
            addressField.setAccessible(true);
            addressField.set(rule, publicIp.getId());

            Field protocolField = rule.getClass().getDeclaredField("protocol");
            protocolField.setAccessible(true);
            protocolField.set(rule, "TCP");

            Field startPortField = rule.getClass().getDeclaredField("publicStartPort");
            startPortField.setAccessible(true);
            startPortField.set(rule, new Integer(443));

            Field endPortField = rule.getClass().getDeclaredField("publicEndPort");
            endPortField.setAccessible(true);
            endPortField.set(rule, new Integer(443));

            Field cidrField = rule.getClass().getDeclaredField("cidrlist");
            cidrField.setAccessible(true);
            cidrField.set(rule, sourceCidrList);

            _firewallService.createIngressFirewallRule(rule);
            _firewallService.applyIngressFwRules(publicIp.getId(), account);

        } catch (Exception e) {
            s_logger.debug("Failed to provision firewall rules for the container cluster: " + containerCluster.getName()
                    + " due to exception: " + getStackTrace(e));
            updateContainerClusterState(containerClusterId, "Error");
            throw new ManagementServerException("Failed to provision firewall rules for the container " +
                    "cluster: " + containerCluster.getName());
        }

        Nic masterVmNic = _networkModel.getNicInNetwork(k8sMasterVM.getId(), containerCluster.getNetworkId());
        final Ip masterIpFinal = new Ip(masterVmNic.getIp4Address());
        final long publicIpId = publicIp.getId();
        final long networkId = containerCluster.getNetworkId();
        final long accountId = account.getId();
        final long domainId = account.getDomainId();
        final long masterVmIdFinal = masterVmId;

        try {
            s_logger.debug("Provisioning port forwarding rule from port 8080 on " + publicIp.getAddress() + " to the master VM IP :" + masterIpFinal);
            PortForwardingRuleVO pfRule = Transaction.execute(new TransactionCallbackWithException<PortForwardingRuleVO, NetworkRuleConflictException>() {
                @Override
                public PortForwardingRuleVO doInTransaction(TransactionStatus status) throws NetworkRuleConflictException {
                    PortForwardingRuleVO newRule =
                            new PortForwardingRuleVO(null, publicIpId,
                                    443, 443,
                                    masterIpFinal,
                                    443, 443,
                                    "tcp", networkId, accountId, domainId, masterVmIdFinal);
                    newRule.setDisplay(true);
                    newRule.setState(FirewallRule.State.Add);
                    newRule = _portForwardingDao.persist(newRule);
                    return newRule;
                }
            });

            _rulesService.applyPortForwardingRules(publicIp.getId(), account);
        } catch (Exception e) {
            s_logger.debug("Failed to activate port forwarding rules " + e);
            updateContainerClusterState(containerClusterId, "Error");
            throw new ManagementServerException("Failed to activate port forwarding rules for the cluster: "
                    + containerCluster.getName() + " due to " + e);
        }

        while (retryCounter < maxRetries) {
            try (Socket socket = new Socket()) {
                s_logger.debug("K8S: Opening up socket: " + publicIp.getAddress().addr() + ":" + 443);
                socket.connect(new InetSocketAddress(publicIp.getAddress().addr(), 443), 10000);
                s_logger.debug("K8S: url available");
                clusterSetup = true;
                break;
            } catch (IOException e) {
                s_logger.debug("K8S: url not available. retry:" + retryCounter + "/" + maxRetries);
                try { Thread.sleep(50000); } catch (Exception ex) {}
                retryCounter++;
            }
        }

        if (clusterSetup) {

            String kubectlUrl = publicIp.getAddress().addr();
            containerCluster.setState("Running");
            _containerClusterDao.update(containerCluster.getId(), containerCluster);

            Runtime r = Runtime.getRuntime();

            int nodePort = 0;
            //get the node port
            //curl -s 192.168.1.202:8080/api/v1/namespaces/kube-system/services/kubernetes-dashboard
            try {
                Process p = r.exec("curl https://" + kubectlUrl + "/api/v1/proxy/namespaces/kube-system/services/kubernetes-dashboard");
                p.waitFor();
                BufferedReader b = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = "";
                while ((line = b.readLine()) != null) {
                    s_logger.debug("KUBECTL: " + line);
                    if (line.contains("nodePort")) {
                        String[] nodeportStr = line.split(":");
                        nodePort = Integer.valueOf(nodeportStr[1].substring(1, nodeportStr[1].length()));
                        s_logger.debug("K8S dashboard service is running on: " + nodePort);
                    }
                }
                b.close();
            } catch (IOException excep) {
                s_logger.error("KUBECTL: " + excep);
            } catch (InterruptedException e) {
                s_logger.error("KUBECTL: " + e);
            }

            containerCluster.setConsoleEndpoint("https://" + publicIp.getAddress() + "/api/v1/proxy/namespaces/kube-system/services/kubernetes-dashboard" );
            _containerClusterDao.update(containerCluster.getId(), containerCluster);

        } else {
            s_logger.debug("Failed to provision master node");
            containerCluster.setState("Error");
            _containerClusterDao.update(containerCluster.getId(), containerCluster);
        }

        return containerCluster;
    }

    @Override
    public boolean deleteContainerCluster(DeleteContainerClusterCmd cmd) {

        ContainerClusterVO cluster = _containerClusterDao.findById(cmd.getId());
        if (cluster == null) {
            throw new InvalidParameterValueException("Invalid cluster id specified");
        }

        CallContext ctx = CallContext.current();
        Account caller = ctx.getCallingAccount();

        _accountMgr.checkAccess(caller, SecurityChecker.AccessType.OperateEntry, false, cluster);

        cluster.setState("Deleting");
        _containerClusterDao.update(cluster.getId(), cluster);

        List<ContainerClusterVmMapVO> clusterVMs = _containerClusterVmMapDao.listByClusterId(cluster.getId());

        boolean failedVmDestroy = false;
        boolean failedNetworkDestroy = false;
        for (ContainerClusterVmMapVO clusterVM: clusterVMs) {
            long vmID = clusterVM.getVmId();
            UserVm userVM = _userVmService.getUserVm(vmID);
            try {
                _userVmService.destroyVm(vmID);
                _userVmService.expungeVm(vmID);
                s_logger.debug("Destroyed VM: " + userVM.getInstanceName() + " as part of cluster: " + cluster.getName() + " destroy.");
                _containerClusterVmMapDao.expunge(clusterVM.getId());
            } catch (Exception e ) {
                failedVmDestroy = true;
                s_logger.error("Failed to destroy VM :" + userVM.getInstanceName() + " part of the cluster: " + cluster.getName() +
                        " due to " + e);
                s_logger.debug("Moving on with destroying remaining resources provisioned for the cluster: " + cluster.getName());
            }
        }

        if(!failedVmDestroy) {
            NetworkVO network = null;
            try {
                network = _networkDao.findById(cluster.getNetworkId());
                Account owner = _accountMgr.getAccount(network.getAccountId());
                User callerUser = _accountMgr.getActiveUser(CallContext.current().getCallingUserId());
                ReservationContext context = new ReservationContextImpl(null, null, callerUser, owner);
                _networkMgr.destroyNetwork(cluster.getNetworkId(), context, true);
                s_logger.debug("Destroyed network: " +  network.getName() + " as part of cluster: " + cluster.getName() + " destroy");
            } catch (Exception e) {
                s_logger.error("Failed to destroy network: " + cluster.getNetworkId() +
                        " as part of cluster: " + cluster.getName() + "  destroy due to " + e);
                failedNetworkDestroy = true;
            }
        }

        if (!failedNetworkDestroy) {
            _containerClusterDao.expunge(cluster.getId());
            s_logger.debug("Container cluster: " + cluster.getName() + " is successfully deleted");
        } else {
            s_logger.error("Container cluster: " + cluster.getName() + " failued to get destroyed as one or more resources in the clusters are not destroyed.");
        }

        return true;
    }


    UserVm createK8SMaster(final ContainerClusterVO containerCluster) throws ManagementServerException,
            ResourceAllocationException, ResourceUnavailableException, InsufficientCapacityException {

        UserVm masterVm = null;

        DataCenter zone = _dcDao.findById(containerCluster.getZoneId());
        ServiceOffering serviceOffering = _offeringDao.findById(containerCluster.getServiceOfferingId());
        VirtualMachineTemplate template = _templateDao.findById(containerCluster.getTemplateId());

        List<Long> networkIds = new ArrayList<Long>();
        networkIds.add(containerCluster.getNetworkId());

        Account owner = _accountDao.findById(containerCluster.getAccountId());

        Network.IpAddresses addrs = new Network.IpAddresses(null, null);

        Map<String, String> customparameterMap = new HashMap<String, String>();

        String hostName = containerCluster.getName() + "-k8s-master";

        String k8sMasterConfig = null;
        try {
            String masterCloudConfig = _globalConfigDao.getValue(CcsConfig.ContainerClusterMasterCloudConfig.key());
            k8sMasterConfig = readFile(masterCloudConfig);
            SecureRandom random = new SecureRandom();
            final String randomPassword = new BigInteger(130, random).toString(32);
            final String password = new String("{{ k8s_master.password }}");
            final String user = new String("{{ k8s_master.user }}");
            k8sMasterConfig = k8sMasterConfig.replace(password, randomPassword);
            k8sMasterConfig = k8sMasterConfig.replace(user, "admin");

            ContainerClusterDetailsVO cluster = Transaction.execute(new TransactionCallback<ContainerClusterDetailsVO>() {

                @Override
                public ContainerClusterDetailsVO doInTransaction(TransactionStatus status) {
                    ContainerClusterDetailsVO clusterDetails = new ContainerClusterDetailsVO(containerCluster.getId(),
                            "admin", randomPassword);
                    _containerClusterDetailsDao.persist(clusterDetails);
                return clusterDetails;
                }
            });
        } catch (Exception e) {
            s_logger.error("Failed to read kubernetes master configuration file");
        }
        s_logger.debug("Config used as user-data for the master VM's: " + k8sMasterConfig);

        String base64UserData = Base64.encodeBase64String(k8sMasterConfig.getBytes());

        s_logger.debug("Provisioning the k8s master VM: " + hostName + " in the container cluster: " + containerCluster.getName());

        masterVm = _userVmService.createAdvancedVirtualMachine(zone, serviceOffering, template, networkIds, owner,
                hostName, containerCluster.getDescription(), null, null, null,
                null, BaseCmd.HTTPMethod.POST, base64UserData, containerCluster.getKeyPair(),
                null, addrs, null, null, null, customparameterMap, null);

        try {
            StartVMCmd startVm = new StartVMCmd();
            startVm = ComponentContext.inject(startVm);
            Field f = startVm.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(startVm, masterVm.getId());
            _userVmService.startVirtualMachine(startVm);
        } catch (ConcurrentOperationException ex) {
            s_logger.error("Failed to launch master VM Exception: ", ex);
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        } catch (ResourceUnavailableException ex) {
            s_logger.error("Failed to launch master VM Exception: ", ex);
            throw new ServerApiException(ApiErrorCode.RESOURCE_UNAVAILABLE_ERROR, ex.getMessage());
        } catch (InsufficientCapacityException ex) {
            s_logger.error("Failed to launch master VM Exception: ", ex);
            throw new ServerApiException(ApiErrorCode.INSUFFICIENT_CAPACITY_ERROR, ex.getMessage());
        } catch (Exception e) {
        }

        masterVm = _vmDao.findById(masterVm.getId());
        if (!masterVm.getState().equals(VirtualMachine.State.Running)) {
            s_logger.error("Failed to start master VM instance.");
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to start master VM instance.");
        }

        return masterVm;
    }


    UserVm createK8SNode(ContainerClusterVO containerCluster, String masterIp, int nodeInstance) throws ManagementServerException,
            ResourceAllocationException, ResourceUnavailableException, InsufficientCapacityException {

        UserVm nodeVm = null;

        DataCenter zone = _dcDao.findById(containerCluster.getZoneId());
        ServiceOffering serviceOffering = _offeringDao.findById(containerCluster.getServiceOfferingId());
        VirtualMachineTemplate template = _templateDao.findById(containerCluster.getTemplateId());

        List<Long> networkIds = new ArrayList<Long>();
        networkIds.add(containerCluster.getNetworkId());

        Account owner = _accountDao.findById(containerCluster.getAccountId());

        Network.IpAddresses addrs = new Network.IpAddresses(null, null);

        Map<String, String> customparameterMap = new HashMap<String, String>();

        String hostName = containerCluster.getName() + "-k8s-node-" + String.valueOf(nodeInstance);

       String k8sNodeConfig = null;
        try {
            String nodeCloudConfig = _globalConfigDao.getValue(CcsConfig.ContainerClusterNodeCloudConfig.key());
            k8sNodeConfig = readFile(nodeCloudConfig).toString();
            s_logger.debug("Config used as user-data for the node VM's: " + k8sNodeConfig);
            String masterIPString = new String("{{ k8s_master.default_ip }}");
            k8sNodeConfig = k8sNodeConfig.replace(masterIPString, masterIp);
        } catch (Exception e) {
            s_logger.error("Failed to read kubernetes node configuration file");
        }

        s_logger.debug("Config used as user-data for the node VM's: " + k8sNodeConfig);

        String base64UserData = Base64.encodeBase64String(k8sNodeConfig.getBytes());

        s_logger.debug("Provisioning the k8s node VM: " + hostName + " in the container cluster: " + containerCluster.getName());

        nodeVm = _userVmService.createAdvancedVirtualMachine(zone, serviceOffering, template, networkIds, owner,
                hostName, containerCluster.getDescription(), null, null, null,
                null, BaseCmd.HTTPMethod.POST, base64UserData, containerCluster.getKeyPair(),
                null, addrs, null, null, null, customparameterMap, null);

        try {
            StartVMCmd startVm = new StartVMCmd();
            startVm = ComponentContext.inject(startVm);
            Field f = startVm.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(startVm, nodeVm.getId());
            _userVmService.startVirtualMachine(startVm);
        } catch (ConcurrentOperationException ex) {
            s_logger.error("Failed to launch node VM Exception: ", ex);
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        } catch (ResourceUnavailableException ex) {
            s_logger.error("Failed to launch node VM Exception: ", ex);
            throw new ServerApiException(ApiErrorCode.RESOURCE_UNAVAILABLE_ERROR, ex.getMessage());
        } catch (InsufficientCapacityException ex) {
            s_logger.error("Failed to launch node VM Exception: ", ex);
            throw new ServerApiException(ApiErrorCode.INSUFFICIENT_CAPACITY_ERROR, ex.getMessage());
        } catch (Exception e ) {
        }

        UserVmVO tmpVm = _vmDao.findById(nodeVm.getId());
        if (!tmpVm.getState().equals(VirtualMachine.State.Running)) {
            s_logger.error("Failed to start node VM instance.");
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to start node VM instance.");
        }

        return nodeVm;
    }

    @Override
    public ListResponse<ContainerClusterResponse>  listContainerClusters(ListContainerClusterCmd cmd) {

        CallContext ctx = CallContext.current();
        Account caller = ctx.getCallingAccount();

        ListResponse<ContainerClusterResponse> response = new ListResponse<ContainerClusterResponse>();

        List<ContainerClusterResponse> responsesList = new ArrayList<ContainerClusterResponse>();

        if (cmd.getId() != null) {
            ContainerClusterVO cluster = _containerClusterDao.findById(cmd.getId());
            if (cluster == null) {
                throw new InvalidParameterValueException("Invalid cluster id specified");
            }

            _accountMgr.checkAccess(caller, SecurityChecker.AccessType.ListEntry, false, cluster);

            responsesList.add(createContainerClusterResponse(cluster));
        } else {
            if (_accountMgr.isAdmin(caller.getId())) {
                List<ContainerClusterVO> containerClusters = _containerClusterDao.listAll();
                for (ContainerClusterVO cluster : containerClusters) {
                    ContainerClusterResponse clusterReponse = createContainerClusterResponse(cluster);
                    responsesList.add(clusterReponse);
                }
            } else {
                List<ContainerClusterVO> containerClusters = _containerClusterDao.listByAccount(caller.getAccountId());
                for (ContainerClusterVO cluster : containerClusters) {
                    ContainerClusterResponse clusterReponse = createContainerClusterResponse(cluster);
                    responsesList.add(clusterReponse);
                }
            }

        }
        response.setResponses(responsesList);
        return response;
    }

    public ContainerClusterResponse createContainerClusterResponse(ContainerCluster containerCluster) {

        ContainerClusterResponse response = new ContainerClusterResponse();

        response.setId(containerCluster.getUuid());

        response.setName(containerCluster.getName());

        response.setDescription(containerCluster.getDescription());

        DataCenterVO zone = ApiDBUtils.findZoneById(containerCluster.getZoneId());
        response.setZoneId(zone.getUuid());
        response.setZoneName(zone.getName());

        response.setClusterSize(String.valueOf(containerCluster.getNodeCount()));

        VMTemplateVO template = ApiDBUtils.findTemplateById(containerCluster.getTemplateId());
        response.setTemplateId(template.getUuid());

        ServiceOfferingVO offering = _srvOfferingDao.findById(containerCluster.getServiceOfferingId());
        response.setServiceOfferingId(offering.getUuid());

        response.setServiceOfferingName(offering.getName());

        response.setKeypair(containerCluster.getKeyPair());

        response.setState(containerCluster.getState());

        response.setCores(String.valueOf(containerCluster.getCores()));

        response.setMemory(String.valueOf(containerCluster.getMemory()));

        response.setObjectName("containercluster");

        NetworkVO ntwk = _networkDao.findByIdIncludingRemoved(containerCluster.getNetworkId());

        response.setEndpoint(containerCluster.getEndpoint());

        response.setNetworkId(ntwk.getUuid());

        response.setAssociatedNetworkName(ntwk.getName());

        response.setConsoleEndpoint(containerCluster.getConsoleEndpoint());

        List<String> vmIds = new ArrayList<String>();
        List<ContainerClusterVmMapVO> vmList = _containerClusterVmMapDao.listByClusterId(containerCluster.getId());
        if (vmList != null && !vmList.isEmpty()) {
            for (ContainerClusterVmMapVO vmMapVO: vmList) {
                UserVmVO userVM = _userVmDao.findById(vmMapVO.getVmId());
                if (userVM != null) {
                    vmIds.add(userVM.getUuid());
                }
            }
        }

        response.setVirtualMachineIds(vmIds);

        ContainerClusterDetailsVO clusterDetails = _containerClusterDetailsDao.findByClusterId(containerCluster.getId());
        if (clusterDetails != null) {
            response.setUsername(clusterDetails.getUserName());
            response.setPassword(clusterDetails.getPassword());
        }

        return response;
    }

}

