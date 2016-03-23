# ConstructR #

[![Join the chat at https://gitter.im/hseeberger/constructr](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/hseeberger/constructr?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

ConstructR aims at cluster bootstrapping (construction) by using a coordination service. Currently it provides libraries for bootstrapping [Akka](http://akka.io) and [Cassandra](https://cassandra.apache.org) clusters via [etcd](https://github.com/coreos/etcd).

Disambiguation: Despite the similar name, ConstructR is not related to [Lightbend ConductR](http://www.lightbend.com/products/conductr).

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

``` scala
// All releases including intermediate ones are published here,
// final ones are also published to Maven Central.
resolvers += Resolver.bintrayRepo("hseeberger", "maven")

libraryDependencies ++= Vector(
  "de.heikoseeberger" %% "constructr-akka" % "0.12.0",
  ...
)
```

Simply add the `ConstructrExtension` to the `extensions` configuration setting:

```
akka.extensions = [de.heikoseeberger.constructr.akka.ConstructrExtension]
```

This will start the `Constructr` actor as a system actor. Alternatively start it yourself as early as possible if you feel so inclined.

The following listing shows the available configuration settings with their defaults:

```
constructr {
  coordination {
    host = localhost
    port = 2379
  }

  coordination-timeout = 3 seconds  // Maximum response time for coordination service (e.g. etcd)
  max-nr-of-seed-nodes = 0          // Any nonpositive value means Int.MaxValue
  nr-of-retries        = 2          // Nr. of tries are nr. of retries + 1
  refresh-interval     = 30 seconds // TTL is refresh-interval * ttl-factor
  retry-delay          = 3 seconds  // Give coordination service (e.g. etcd) some delay before retrying
  ttl-factor           = 2.0        // Must be greater or equal 1 + ((coordination-timeout * (1 + nr-of-retries) + retry-delay * nr-of-retries)/ refresh-interval)!

  join-timeout = 15 seconds // Might depend on cluster size and network properties
}
```

## ConstructR for Cassandra

``` scala
// All releases including intermediate ones are published here,
// final ones are also published to Maven Central.
resolvers += Resolver.bintrayRepo("hseeberger", "maven")

libraryDependencies ++= Vector(
  "de.heikoseeberger" %% "constructr-cassandra" % "0.12.0",
  ...
)
```

Simply (LOL) configure the `ConstructrSeedProvider` under the `seed_provider` configuration setting:

```
seed_provider:
    - class_name: de.heikoseeberger.constructr.cassandra.ConstructrSeedProvider
```

If you want to run Cassandra in Docker, ConstructR provides the [constructr/cassandra-3.3](https://hub.docker.com/r/constructr/cassandra-3.3) Docker image with the necessary configuration.

The following listing shows the available configuration settings with their defaults:

```
constructr {
  coordination {
    host = localhost
    host = ${?CASSANDRA_BROADCAST_ADDRESS} // Works for Docker image
    port = 2379
  }

  coordination-timeout = 3 seconds  // Maximum response time for coordination service (e.g. etcd)
  max-nr-of-seed-nodes = 0          // Any nonpositive value means Int.MaxValue
  nr-of-retries        = 2          // Nr. of tries are nr. of retries + 1
  refresh-interval     = 30 seconds // TTL is refresh-interval * ttl-factor
  retry-delay          = 3 seconds  // Give coordination service (e.g. etcd) some delay before retrying
  ttl-factor           = 2.0        // Must be greater or equal 1 + ((coordination-timeout * (1 + nr-of-retries) + retry-delay * nr-of-retries)/ refresh-interval)!

  cluster-name          = default                         // Must match cluster_name in cassandra.yaml!
  cluster-name          = ${?CASSANDRA_CLUSTER_NAME}      // Works for Docker image
  seed-provider-timeout = 20 seconds                      // Should be longer than coordination-timeout
  self-address          = auto                            // "auto" means `InetAddress.getLocalHost`
  self-address          = ${?CASSANDRA_BROADCAST_ADDRESS} // Works for Docker image
}
```

## Coordination

ConstructR comes with out-of-the-box support for etcd: simply depend on the "constructr-coordination-etcd" module. If you want to use some other coordination backend, e.g. Consul, simply implement the `Coordination` trait from the "constructr-coordination" module and make sure to provide the fully qualifeid class name via the `constructr.coordination.class-name` configuration setting.

## Testing

Requirements:
  - etcd needs to be running, e.g. via `docker run -d -p 2379:2379 quay.io/coreos/etcd:v2.2.5 -advertise-client-urls http://192.168.99.100:2379 -listen-client-urls http://0.0.0.0:2379`

## Contribution policy ##

Contributions via GitHub pull requests are gladly accepted from their original author. Along with any pull requests, please state that the contribution is your original work and that you license the work to the project under the project's open source license. Whether or not you state this explicitly, by submitting any copyrighted material via pull request, email, or other means you agree to license the material under the project's open source license and warrant that you have the legal authority to do so.

Please make sure to follow these conventions:
- For each contribution there must be a ticket (GitHub issue) with a short descriptive name, e.g. "Respect seed-nodes configuration setting"
- Work should happen in a branch named "ISSUE-DESCRIPTION", e.g. "32-respect-seed-nodes"
- Before a PR can be merged, all commits must be squashed into one with its message made up from the ticket name and the ticket id, e.g. "Respect seed-nodes configuration setting (closes #32)"

## License ##

This code is open source software licensed under the [Apache 2.0 License](http://www.apache.org/licenses/LICENSE-2.0.html).
