# connection_rebalancer_app

This project uses Quarkus, the Supersonic Subatomic Java Framework.

The purpose of this project is to read the state of connections from consul services and redis to scale servers/containers according to the connection count.
Furthermore, send Redis Pub/sub admin commands for specific servers to shed connections with the purpose of rebalancing the network connection topology.

Bellow is a visual representation of the rebalancing logic & Scaling logic.

### Rebalancing Connections

```mermaid
flowchart TD
    Start((Start)) --> A[Read available services\nfrom consul server]
    A --> B[Read connection topology\nfrom redis server]
    B --> C[Iterate all available servers]
    C --> D{Is server utilization\n> overall util +\ntolerance 10%?}
    D -- Y --> E[Add Server to\nOverutilized list]
    E --> F[Sort Overutilized\nServers]
    F --> Merge((Merge))
    D -- N --> G{Is server\nutilization\n< overall\nutilization?}
    G -- Y --> H[Add Server to\nUnderutilized list]
    H --> I[Sort Underutilized\nservers]
    I --> Merge
    G -- N --> Merge
    Merge --> J[Calculate Number of sessions\nto drop from overutilized\nservers.]
    J --> K[Send PUB command to redis\nbackplane to drop Sessions]
    K --> L[Servers Consume SUB redis\ncommands and elect the\n'Victims' to drop sessions.]
    L --> M{Are there\nservers to\niterate?}
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
    Start((Start)) --> A[Read available services\nfrom consul server]
    A --> B[Read connection topology\nfrom redis server]
    B --> C{Is Overall\nutilization\n> 70%?}
    C -- Y --> D[Calculate Number\nof Servers needed]
    D --> E{Is there\nInactive\nservers?}
    E -- Y --> F[Reactivate\nInactive servers]
    F --> H{N of servers\nreach the\nserver target?}
    E -- N --> G[Command to Scale out\nservers - Docker]
    G --> H
    H -- N --> E
    H -- Y --> Merge((Merge))
    C -- N --> I{Is Overall\nutilization\n< 40%?}
    I -- Y --> J[Calculate Number\nof Servers needed]
    J --> K[Command to Scale in servers\nInactivate/Cord on server\nin Consul]
    K --> Merge
    I -- N --> Merge
    Merge --> L{Is there Inactive\nServers with no\nremaining connections?}
    L -- Y --> M[Kill Inactive servers\nStop docker containers]
    M --> End((End))
    L -- N --> End

    style Start fill:#fff,stroke:#000
    style End fill:#000,stroke:#000,color:#fff
```
