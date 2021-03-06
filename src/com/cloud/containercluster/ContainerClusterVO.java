/*
 * Copyright 2016 ShapeBlue Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloud.containercluster;

import java.util.Date;
import java.util.UUID;


import javax.persistence.Column;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;

import com.cloud.utils.db.GenericDao;

@Entity
@Table(name = "sb_ccs_container_cluster")
public class ContainerClusterVO implements ContainerCluster {

    @Override
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    @Override
    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public long getZoneId() {
        return zoneId;
    }

    public void setZoneId(long zoneId) {
        this.zoneId = zoneId;
    }

    @Override
    public long getServiceOfferingId() {
        return serviceOfferingId;
    }

    public void setServiceOfferingId(long serviceOfferingId) {
        this.serviceOfferingId = serviceOfferingId;
    }

    @Override
    public long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(long templateId) {
        this.templateId = templateId;
    }

    @Override
    public long getNetworkId() {
        return networkId;
    }

    public void setNetworkId(long networkId) {
        this.networkId = networkId;
    }

    @Override
    public long getDomainId() {
        return domainId;
    }

    public void setDomainId(long domainId) {
        this.domainId = domainId;
    }

    @Override
    public long getAccountId() {
        return accountId;
    }

    public void setAccountId(long accountId) {
        this.accountId = accountId;
    }

    @Override
    public long getNodeCount() {
        return nodeCount;
    }

    public void setNodeCount(long nodeCount) {
        this.nodeCount = nodeCount;
    }

    @Override
    public long getCores() {
        return cores;
    }

    public void setCores(long cores) {
        this.cores = cores;
    }

    @Override
    public long getMemory() {
        return memory;
    }

    public void setMemory(long memory) {
        this.memory = memory;
    }

    @Override
    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    @Override
    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getKeyPair() {
        return keyPair;
    }

    public void setKeyPair(String keyPair) {
        this.keyPair = keyPair;
    }

    @Override
    public String getConsoleEndpoint() {
        return consoleEndpoint;
    }

    public void setConsoleEndpoint(String consoleEndpoint) {
        this.consoleEndpoint = consoleEndpoint;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    long id;

    @Column(name = "uuid")
    String uuid;

    @Column(name = "name")
    private String name;

    @Column(name = "description", length = 4096)
    private String description;

    @Column(name = "zone_id")
    long zoneId;

    @Column(name = "service_offering_id")
    long serviceOfferingId;

    @Column(name = "template_id")
    long templateId;

    @Column(name = "network_id")
    long networkId;

    @Column(name = "domain_id")
    protected long domainId;

    @Column(name = "account_id")
    protected long accountId;

    @Column(name = "node_count")
    long nodeCount;

    @Column(name = "cores")
    long cores;

    @Column(name = "memory")
    long memory;

    @Column(name = "state")
    State  state;

    @Column(name = "key_pair")
    String keyPair;

    @Column(name = "endpoint")
    String endpoint;

    @Column(name = "console_endpoint")
    String consoleEndpoint;

    @Column(name = GenericDao.CREATED_COLUMN)
    protected Date created;

    @Column(name = GenericDao.REMOVED_COLUMN)
    protected Date removed;

    @Column(name = "gc")
    boolean checkForGc;

    public ContainerClusterVO() {

    }

    public ContainerClusterVO(String name, String description, long zoneId, long serviceOfferingId, long templateId,
                               long networkId, long domainId, long accountId, long nodeCount, State state,
                              String keyPair, long cores, long memory, String endpoint, String consoleEndpoint) {
        this.uuid = UUID.randomUUID().toString();
        this.name = name;
        this.description = description;
        this.zoneId = zoneId;
        this.serviceOfferingId = serviceOfferingId;
        this.templateId = templateId;
        this.networkId = networkId;
        this.domainId = domainId;
        this.accountId = accountId;
        this.nodeCount = nodeCount;
        this.state = state;
        this.keyPair = keyPair;
        this.cores = cores;
        this.memory = memory;
        this.endpoint = endpoint;
        this.consoleEndpoint = consoleEndpoint;
        this.checkForGc = false;
    }

    @Override
    public Class<?> getEntityType() {
        return ContainerCluster.class;
    }

    @Override
    public boolean isDisplay() {
        return true;
    }


    public Date getRemoved() {
        if (removed == null)
            return null;
        return new Date(removed.getTime());
    }

    public boolean ischeckForGc() {
        return checkForGc;
    }

    public void setCheckForGc(boolean check) {
        checkForGc = check;
    }

}
