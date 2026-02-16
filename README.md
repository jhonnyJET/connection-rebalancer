# Connection Rebalancer App

The purpose of this project is to read the state of connections from consul services and redis to scale servers/containers according to the connection count.
Furthermore, send Redis Pub/sub admin commands for specific servers to shed connections with the purpose of rebalancing the network connection topology.

Bellow is a visual representation of the rebalancing logic & Scaling logic.

### Rebalancing Connections

```mermaid
flowchart TD
    Start((Start)) --> A[Read available servicesfrom consul server]
    A --> B[Read connection topology from redis server]
    B --> C[Iterate all available servers]
    C --> D{Is server utilization> overall util +tolerance 10%?}
    D -- Y --> E[Add Server toOverutilized list]
    E --> F[Sort OverutilizedServers]
    F --> Merge((Merge))
    D -- N --> G{Is serverutilization< overallutilization?}
    G -- Y --> H[Add Server toUnderutilized list]
    H --> I[Sort Underutilizedservers]
    I --> Merge
    G -- N --> Merge
    Merge --> J[Calculate Number of sessionsto drop from overutilizedservers.]
    J --> K[Send PUB command to redisbackplane to drop Sessions]
    K --> L[Servers Consume SUB rediscommands and elect the'Victims' to drop sessions.]
    L --> M{Are thereservers toiterate?}
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
    Start((Start)) --> A[Read available servicesfrom consul server]
    A --> B[Read connection topologyfrom redis server]
    B --> C{Is Overallutilization> 70%?}
    C -- Y --> D[Calculate Numberof Servers needed]
    D --> E{Is thereInactiveservers?}
    E -- Y --> F[ReactivateInactive servers]
    F --> H{N of serversreach theserver target?}
    E -- N --> G[Command to Scale outservers - Docker]
    G --> H
    H -- N --> E
    H -- Y --> Merge((Merge))
    C -- N --> I{Is Overallutilization< 40%?}
    I -- Y --> J[Calculate Numberof Servers needed]
    J --> K[Command to Scale in serversInactivate/Cord on serverin Consul]
    K --> Merge
    I -- N --> Merge
    Merge --> L{Is there InactiveServers with noremaining connections?}
    L -- Y --> M[Kill Inactive serversStop docker containers]
    M --> End((End))
    L -- N --> End

    style Start fill:#fff,stroke:#000
    style End fill:#000,stroke:#000,color:#fff
```
