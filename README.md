# Connection Rebalancer App

The purpose of this project is to read the state of connections from consul services and redis to scale servers/containers according to the connection count.
Furthermore, send Redis Pub/sub admin commands for specific servers to shed connections with the purpose of rebalancing the network connection topology.

Bellow is a visual representation of the rebalancing logic & Scaling logic.

### Rebalancing Connections

```mermaid
flowchart TD
    Start((Start)) --> A[Read available services from consul server]
    A --> B[Read connection topology from redis server]
    B --> C[Iterate all available servers]
    C --> D{Is server utilization > overall util + tolerance 10%?}
    D -- Y --> E[Add Server toOverutilized list]
    E --> F[Sort OverutilizedServers]
    F --> Merge((Merge))
    D -- N --> G{Is server utilization < overallutilization?}
    G -- Y --> H[Add Server to Underutilized list]
    H --> I[Sort Underutilized servers]
    I --> Merge
    G -- N --> Merge
    Merge --> J[Calculate Number of sessions to drop from overutilized servers.]
    J --> K[Send PUB command to redis backplane to drop Sessions]
    K --> L[Servers Consume SUB redis commands and elect the 'Victims' to drop sessions.]
    L --> M{Are there servers to iterate?}
    M -- Y --> D
    M -- N --> End((End))

    style Start fill:#fff,stroke:#000
    style End fill:#000,stroke:#000,color:#fff
```

> **Formulas:**
> - `activeSessionAverageThreshold = ROUNDUP(maxSessionPerHost * overallUtilizationPercent)`
> - `sessionsToOffload = activeSessions - activeSessionAverageThreshold`

---

### Scaling Connections

```mermaid
flowchart TD
    Start((Start)) --> A[Read available services from consul server]
    A --> B[Read connection topology from redis server]
    B --> C{Is Overall utilization> 70%?}
    C -- Y --> D[Calculate Number of Servers needed]
    D --> E{Is there Inactive servers?}
    E -- Y --> F[Reactivate Inactive servers]
    F --> H{N of servers reach the server target?}
    E -- N --> G[Command to Scale out servers - Docker]
    G --> H
    H -- N --> E
    H -- Y --> Merge((Merge))
    C -- N --> I{Is Overall utilization< 40%?}
    I -- Y --> J[Calculate Number of Servers needed]
    J --> K[Command to Scale in services in Consul]
    K --> Merge
    I -- N --> Merge
    Merge --> L{Is there Inactive Servers with no remaining connections?}
    L -- Y --> M[Kill Inactive servers stop docker containers]
    M --> End((End))
    L -- N --> End

    style Start fill:#fff,stroke:#000
    style End fill:#000,stroke:#000,color:#fff
```
