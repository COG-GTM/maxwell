# High Availabilty

As v1.29.1, Maxwell contains experiemental (alpha quality) client-side HA.
Support for performing leader elections is done via
[jgroups-raft](https://github.com/belaban/jgroups-raft).

## Getting started

First, copy `raft.xml.example` to `raft.xml`.  Edit to your liking, paying attention to:

```
    <raft.RAFT members="A,B,C" raft_id="${raft_id:undefined}"/>
```

Note that because we are using a RAFT-based leader election, we will have to spin up at least
3 maxwell client nodes.

### Securing the cluster

The raft election decides which node runs replication.  Anyone who can join the
cluster can vote, so they can hold leadership (no node replicates) or keep
forcing elections (Maxwell starts and stops over and over).  JGroups does no
authentication by default, so the example config locks the cluster down:

- **TCP + TCPPING**: nodes find each other through a fixed list of `host[port]`
  peers instead of UDP multicast.
- **SYM_ENCRYPT**: all cluster traffic is encrypted with a shared AES key.  A node
  without the key can't read or inject messages.
- **AUTH** (`X509Token`): a JOIN must present a shared secret, encrypted with an
  RSA key pair that only cluster members hold.

On top of that, put the HA nodes on an isolated network segment or firewall the
TCP port so only the other HA nodes can reach it.

Generate one keystore holding an AES key (encryption) and an RSA key pair
(AUTH), and copy it to every node over a secure channel.  Use the same password
for the keystore and both keys:

```bash
keytool -genseckey -alias maxwell-ha -keyalg AES -keysize 256 \
        -storetype PKCS12 -keystore raft.keystore
keytool -genkeypair -alias maxwell-ha-auth -keyalg RSA -keysize 3072 \
        -dname "CN=maxwell-ha" -validity 3650 \
        -storetype PKCS12 -keystore raft.keystore
chmod 600 raft.keystore
```

The config reads these settings from JVM system properties (or environment
variables with the same name):

property                            | default                                             | description
----------------------------------- | --------------------------------------------------- | -----------
`jgroups.bind_addr`                 | `127.0.0.1`                                         | address this node listens on
`jgroups.bind_port`                 | `7800`                                              | TCP port this node listens on
`jgroups.tcpping.initial_hosts`     | `127.0.0.1[7800],127.0.0.1[7801],127.0.0.1[7802]`   | every HA node, as `host[port]`
`jgroups.encrypt.keystore`          | `raft.keystore`                                     | path to the keystore holding the AES key and RSA key pair
`jgroups.encrypt.keystore_type`     | `PKCS12`                                            | keystore type
`jgroups.encrypt.keystore_password` | *(required)*                                        | keystore (and key) password
`jgroups.encrypt.alias`             | `maxwell-ha`                                        | alias of the AES key
`jgroups.auth.alias`                | `maxwell-ha-auth`                                   | alias of the RSA key pair used by AUTH
`jgroups.auth.secret`               | *(required)*                                        | shared secret every node presents on JOIN

The keystore password and auth secret have no defaults.  A node started without
them fails to start, so it can't join the cluster unprotected.  Pass them from
your secret store, not on a shared command line.  Maxwell logs a warning at
startup if the configured stack has no encryption, no AUTH, or uses UDP multicast.

Now start each of your HA maxwell nodes like this:

```bash
export JGROUPS_PEERS="10.0.0.1[7800],10.0.0.2[7800],10.0.0.3[7800]"
export JAVA_OPTS="-Djgroups.tcpping.initial_hosts=$JGROUPS_PEERS \
  -Djgroups.encrypt.keystore_password=$RAFT_KEYSTORE_PASSWORD \
  -Djgroups.auth.secret=$RAFT_AUTH_SECRET"

host1: $ JAVA_OPTS="$JAVA_OPTS -Djgroups.bind_addr=10.0.0.1" bin/maxwell --ha --raft_member_id=A
host2: $ JAVA_OPTS="$JAVA_OPTS -Djgroups.bind_addr=10.0.0.2" bin/maxwell --ha --raft_member_id=B
host3: $ JAVA_OPTS="$JAVA_OPTS -Djgroups.bind_addr=10.0.0.3" bin/maxwell --ha --raft_member_id=C
```

To try it out with all three nodes on one machine, keep the default loopback
peer list and give each node its own port instead of its own address:
`-Djgroups.bind_port=7800`, `7801` and `7802`.

if all goes well, the 3 nodes will connect over TCP, elect one to be the
cluster leader, and away you will go.  If one node is terminated or
partitioned, a new election will be held to replace it.

If you previously ran from a multicast/UDP `raft.xml`, every node has to move to
the new config at the same time.  Nodes with and without encryption can't talk
to each other.


## Getting deeper

More advanced (especially inter-DC) configurations may be implemented by
editing `raft.xml`; you may need to tunnel through a firewall or two, or swap
SYM_ENCRYPT for ASYM_ENCRYPT, good stuff like that.  It's of course out of scope for this document, so
[check out the jgroups
documentation](http://www.jgroups.org/manual5/index.html), but if
you come up with something good drop me a line.

## Common problems

Something I encountered right out of the gate was this:

```
12:37:53,135 WARN  UDP - failed to join /224.0.75.75:7500 on utun0: java.net.SocketException: Can't assign requested address
```

which can be worked around by forcing the JVM onto an ipv4 stack:

```
JAVA_OPTS="-Djava.net.preferIPv4Stack=true" bin/maxwell --ha --raft_member_id=B
```






