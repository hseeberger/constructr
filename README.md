# ConstructR #

ConstructR aims at cluster bootstrapping (construction) by using a coordination service. Currently it provides libraries for bootstrapping [Akka](http://akka.io) and [Cassandra](https://cassandra.apache.org) clusters via [etcd](https://github.com/coreos/etcd) and [Consul](https://www.consul.io).

Disambiguation: ConstructR is not related to [Typesafe ConductR](http://www.typesafe.com/products/conductr), which is a feature-rich and reactive application manager providing deployments, service lookups, health checks and much more.

ConstructR utilizes a key-value coordination service like etcd to automate bootstrapping or joining a cluster. It stores each member node under the key `/constructr/$prefix/$clusterName/nodes/$address` where `$prefix` represents the system to be clustered, e.g. "akka", `$clusterName` is for disambiguating multiple clusters and `$address` is a Base64 encoded address, e.g. `Address` for Akka. These keys expire after a configurable time in order to avoid stale information. Therefore ConstructR refreshes each key periodically.

In a nutshell, ConstructR is a state machine which first tries to get the nodes from the coordination service. If none are available it tries to acquire a lock, e.g. via a CAS write for etcd, and uses itself or retries getting the nodes. Then it joins using these nodes as seed nodes. After that it adds its address to the nodes and starts the refresh loop:

```
    ┌───────────────────┐              ┌───────────────────┐
    │   GettingNodes    │◀─────────────│BeforeGettingNodes │
    └───────────────────┘    delayed   └───────────────────┘
              │     │                            ▲
    non-empty │     └──────────────────────┐     │ failure
              ▼               empty        ▼     │
    ┌───────────────────┐              ┌───────────────────┐
    │      Joining      │◀─────────────│      Locking      │
    └───────────────────┘    success   └───────────────────┘
              │
member-joined │
              ▼
    ┌───────────────────┐
    │    AddingSelf     │
    └───────────────────┘
              │     ┌────────────────────────────┐
              │     │                            │
              ▼     ▼                            │
    ┌───────────────────┐              ┌───────────────────┐
    │ RefreshScheduled  │─────────────▶│    Refreshing     │
    └───────────────────┘              └───────────────────┘
```

If something goes wrong, e.g. a timeout (after configurable retries are exhausted) when interacting with the coordination service, ConstructR by default terminates its `ActorSystem`. At least for constructr-akka this can be changed by providing a custom `SupervisorStrategy` to the manually started `Constructr` actor, but be sure you know what you are doing.

## ConstructR for Akka

constructr-akka depends on Akka 2.4.1 and is published to Bintray and Maven Central.

``` scala
// All releases including intermediate ones are published here,
// final ones are also published to Maven Central.
resolvers += Resolver.bintrayRepo("hseeberger", "maven")

libraryDependencies ++= List(
  "de.heikoseeberger" %% "constructr-akka" % "0.7.1",
  ...
)
```

Simply add the `ConstructrExtension` to the `extensions` configuration setting:

```
akka.extensions = ["de.heikoseeberger.constructr.ConstructrExtension"]
```

This will start the `Constructr` actor as a system actor. Alternatively start it yourself as early as possible if you feel so inclined.

The following listing shows the available configuration settings with their defaults:

```
constructr.akka {
  coordination {
    backend = "etcd"      // Or "consul"
    host    = "localhost"
    port    = 2379
  }

  coordination-retries  = 2
  coordination-timeout  = 3 seconds
  max-nr-of-seed-nodes  = 0          // Any non-positive value means Int.MaxValue
  refresh-interval      = 30 seconds // TTL is refresh-interval * ttl-factor
  retry-get-nodes-delay = 3 seconds
  ttl-factor            = 1.5        // Must be greater than 1 + (coordination-timeout * (1 + coordination-retries) / refresh-interval)!

  join-timeout          = 10 seconds // Might depend on cluster size and network properties
}
```

## ConstructR for Cassandra

constructr-cassandra depends on Cassandra 3.0.0 and is published to Bintray and Maven Central.

``` scala
// All releases including intermediate ones are published here,
// final ones are also published to Maven Central.
resolvers += Resolver.bintrayRepo("hseeberger", "maven")

libraryDependencies ++= List(
  "de.heikoseeberger" %% "constructr-cassandra" % "0.7.1",
  ...
)
```

Simply (LOL) configure the `ConstructrSeedProvider` under the `seed_provider` configuration setting:

```
seed_provider:
    - class_name: de.heikoseeberger.constructr.cassandra.ConstructrSeedProvider
```

If you want to run Cassandra in Docker, ConstructR provides the [constructr/cassandra-3.0.0](https://hub.docker.com/r/constructr/cassandra-3.0.0) Docker image with the necessary configuration.

The following listing shows the available configuration settings with their defaults:

```
constructr.cassandra {
  coordination {
    backend = "etcd"                          // Or "consul"
    host    = "localhost"
    host    = ${?CASSANDRA_BROADCAST_ADDRESS} // Works for Docker image
    port    = 2379
  }

  coordination-retries  = 2
  coordination-timeout  = 3 seconds
  max-nr-of-seed-nodes  = 0          // Any non-positive value means Int.MaxValue
  refresh-interval      = 30 seconds // TTL is refresh-interval * ttl-factor
  retry-get-nodes-delay = 3 seconds
  ttl-factor            = 1.5        // Must be greater than 1 + (coordination-timeout * (1 + coordination-retries) / refresh-interval)!

  cluster-name          = "default"                       // Must match cluster_name in cassandra.yaml!
  cluster-name          = ${?CASSANDRA_CLUSTER_NAME}      // Works for Docker image
  seed-provider-timeout = 20 seconds                      // Should be longer than coordination-timeout
  self-address          = "auto"                          // "auto" means `InetAddress.getLocalHost`
  self-address          = ${?CASSANDRA_BROADCAST_ADDRESS} // Works for Docker image
}
```

## Testing

Requirements:
  - `docker` and `docker-machine` have to be installed, e.g. via the [Docker Toolbox](https://www.docker.com/docker-toolbox)
  - A Docker machine named "default" has to be stated, e.g. via `docker-machine start default`
  - The Docker environment has to be set up, e.g. via `eval "$(docker-machine env default)"`

## Contribution policy ##

Contributions via GitHub pull requests are gladly accepted from their original author. Along with any pull requests, please state that the contribution is your original work and that you license the work to the project under the project's open source license. Whether or not you state this explicitly, by submitting any copyrighted material via pull request, email, or other means you agree to license the material under the project's open source license and warrant that you have the legal authority to do so.

Please make sure to follow these conventions:
- For each contribution there must be a ticket (GitHub issue) with a short descriptive name, e.g. "Respect seed-nodes configuration setting"
- Work should happen in a branch named "ISSUE-DESCRIPTION", e.g. "32-respect-seed-nodes"
- Before a PR can be merged, all commits must be squashed into one with its message made up from the ticket name and the ticket id, e.g. "Respect seed-nodes configuration setting (closes #32)"

## License ##

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
