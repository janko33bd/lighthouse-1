package lighthouse

import com.google.common.io.BaseEncoding
import com.subgraph.orchid.TorClient
import javafx.beans.property.SimpleBooleanProperty
import lighthouse.files.AppDirectory
import lighthouse.wallet.PledgingWallet
import org.bitcoinj.core.*
import org.bitcoinj.core.listeners.AbstractPeerEventListener
import org.bitcoinj.core.listeners.PeerDataEventListener
import org.bitcoinj.net.discovery.DnsDiscovery
import org.bitcoinj.net.discovery.HttpDiscovery
import org.bitcoinj.params.RegTestParams
import org.bitcoinj.params.UnitTestParams
import org.bitcoinj.store.BlockStoreException
import org.bitcoinj.store.FullPrunedBlockStore
import org.bitcoinj.wallet.WalletProtobufSerializer
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.KeyChainGroup
import org.blackcoinj.store.LevelDBStoreFullPrunedBlackstore
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

public class ChainFileLockedException : Exception()

interface IBitcoinBackend {
    public val wallet: PledgingWallet
    public val store: FullPrunedBlockStore
    public val chain: FullPrunedBlockChain
    public val peers: PeerGroup
    //public val xtPeers: PeerGroup
}

/**
 * Class that does similar things to WalletAppKit, but more customised for our needs.
 */
public class BitcoinBackend @Throws(ChainFileLockedException::class) constructor(val context: Context,
                                                                                 val appName: String,
                                                                                 val appVersion: String,
                                                                                 val cmdLineRequestedIPs: List<String>?,
                                                                                 val useTor: Boolean) : IBitcoinBackend {
    private val log = LoggerFactory.getLogger(javaClass)

    public val params: NetworkParameters = context.params

    private val chainFile: File = AppDirectory.dir().resolve("$appName.spvchain").toFile()
    private val walletFile: File = AppDirectory.dir().resolve("$appName.wallet").toFile()

    public override var wallet: PledgingWallet = createOrLoadWallet(walletFile, walletFile.exists() and !chainFile.exists())
    public override var store: FullPrunedBlockStore = initializeChainStore(chainFile)
    public override var chain: FullPrunedBlockChain = FullPrunedBlockChain(context, wallet, store)
    public override var peers: PeerGroup = createPeerGroup()

    //public override var xtPeers: PeerGroup = createXTPeers()
    public val localNodeUnusable: SimpleBooleanProperty = SimpleBooleanProperty()
    public val offline: SimpleBooleanProperty = SimpleBooleanProperty()

    private fun createXTPeers(): PeerGroup {
        val NUM_XT_PEERS = 4
        return if (params === UnitTestParams.get() || params === RegTestParams.get()) {
            peers
        } else {
            with(PeerGroup(context)) {
                // PeerGroup will use a local Bitcoin node if at all possible, but it may not have what we need.
                addEventListener(object : AbstractPeerEventListener() {
                    override fun onPeerConnected(peer: Peer, peerCount: Int) {
                        if (peer.address.addr.isLoopbackAddress && !peer.peerVersionMessage.isGetUTXOsSupported) {
                            // We connected to localhost but it doesn't have what we need.
                            log.warn("Localhost peer does not have support for NODE_GETUTXOS, ignoring")
                            useLocalhostPeerWhenPossible = false
                            maxConnections = NUM_XT_PEERS
                            peer.close()
                            localNodeUnusable.set(true)
                        }
                    }
                })
                // There's unfortunately no way to instruct the other seeds to search for a subset of the Bitcoin network
                // so that's why we need to use a new more flexible HTTP based protocol here. The seed will find
                // Bitcoin XT nodes as people start and stop them.
                //
                // Hopefully in future more people will run HTTP seeds, then we can use a MultiplexingDiscovery
                // to randomly merge their answers and reduce the influence of any one seed. Additionally if
                // more people run Bitcoin XT nodes we can bump up the number we search for here to again
                // reduce the influence of any one node. But this needs people to help decentralise.
                val uri = URI("http://main.seed.vinumeris.com/peers?srvmask=3&getutxo=true")
                val authKey = ECKey.fromPublicOnly(BaseEncoding.base16().decode("027a79143a4de36341494d21b6593015af6b2500e720ad2eda1c0b78165f4f38c4".toUpperCase()))
                addPeerDiscovery(HttpDiscovery(params, uri, authKey))
                setConnectTimeoutMillis(10000)
                maxConnections = NUM_XT_PEERS
                setUserAgent(appName, appVersion)
                this
            }
        }
    }

    private fun createPeerGroup(): PeerGroup {
        val pg = if (!useTor) {
            with(PeerGroup(context, chain)) {
                  // Main or test network
                    if (cmdLineRequestedIPs == null)
                        addPeerDiscovery(DnsDiscovery(params))
                    else
                        for (ip in cmdLineRequestedIPs)
                            addAddress(InetAddress.getByName(ip))

                this
            }
        } else {
            val torClient = TorClient()
            torClient.config.dataDirectory = AppDirectory.dir().toFile()
            PeerGroup.newWithTor(context, chain, torClient)
        }

        pg.addWallet(wallet)
        pg.setUserAgent(appName, appVersion)
        return pg
    }

    private fun initializeChainStore(file: File, newSeed: DeterministicSeed? = null): FullPrunedBlockStore {
        try {
//              if (fileIsNew) {
//                // We need to checkpoint the new file to speed initial sync.
//                val time = newSeed?.creationTimeSeconds ?: wallet.earliestKeyCreationTime
//                val stream = WalletAppKit::class.java.getResourceAsStream("/" + params.id + ".checkpoints")
//                if (stream != null) {
//                    CheckpointManager.checkpoint(params, stream, store, time)
//                    stream.close()
//                }
//            }
            var chainFile = File("forwarding-service.spvchain")
            val chainPath = chainFile.absolutePath.replace("\\", "/")
            log.info(chainPath)
            return LevelDBStoreFullPrunedBlackstore(params, chainPath)
        } catch(e: BlockStoreException) {
            if (e.message?.contains("locked") ?: false)
                throw ChainFileLockedException()
            else
                throw e
        }
    }

    private fun createOrLoadWallet(walletFile: File, shouldReplayWallet: Boolean, newSeed: DeterministicSeed? = null): PledgingWallet {
        val exists = walletFile.exists()
        if (newSeed != null && exists)
            moveOldWalletToBackup()
        val wallet = if (exists) {
            walletFile.inputStream().use {
                val serializer = WalletProtobufSerializer() { params, kcg -> PledgingWallet(params, kcg) }
                serializer.readWallet(it) as PledgingWallet
            }
        } else {
            if (newSeed != null)
                PledgingWallet(params, KeyChainGroup(params, newSeed))
            else
                PledgingWallet(params)
        }
        if (shouldReplayWallet)
            wallet.reset()
        wallet.autosaveToFile(walletFile, 1, TimeUnit.SECONDS, null)
        return wallet
    }

    private fun moveOldWalletToBackup() {
        var counter = 1
        var newName: File
        do {
            newName = File(walletFile.getParent(), "Backup " + counter + " for " + walletFile.name)
            counter++
        } while (newName.exists())
        log.info("Renaming old wallet file $walletFile to $newName")
        if (!walletFile.renameTo(newName)) {
            // This should not happen unless something is really messed up.
            throw RuntimeException("Failed to rename wallet for restore")
        }
    }

    private @Volatile var running: Boolean = false
    private var downloadListener: PeerDataEventListener? = null

    public fun start(downloadListener: PeerDataEventListener) {
        log.info("Start request received")
        this.downloadListener = downloadListener
        // Assume google.com is the most reliable DNS name in the world.
        log.info("Doing google.com DNS check to see if we're online")
        if (InetSocketAddress("google.com", 80).address == null) {
            log.warn("User appears to be offline")
            synchronized(offline) {
                offline.set(true)
            }
            return
        }

        log.info("Starting regular peer group")
        peers.start()
//        if (xtPeers !== peers) {
//            log.info("Starting XT peer group")
//            xtPeers.start()
//        }
        running = true
        log.info("Starting block chain download")
        peers.startBlockChainDownload(downloadListener)
    }

    public fun isOffline(): Boolean = synchronized(offline) { offline.get() }

    public fun restoreFromSeed(seed: DeterministicSeed) {
        check(running)
        check(!isOffline())
        stop()

        moveOldWalletToBackup()

        chainFile.delete()
        wallet = createOrLoadWallet(walletFile, true, seed)
        store = initializeChainStore(chainFile, seed)
        chain = FullPrunedBlockChain(context, wallet, store)
        peers = createPeerGroup()
        //xtPeers = createXTPeers()

        start(downloadListener!!)
    }

    public fun stop() {
        if (running) {
            peers.stop()
//            if (peers !== xtPeers)
//                xtPeers.stopAsync()
            wallet.saveToFile(walletFile)
        }
        store.close()
        running = false
    }

    public fun wallet(): PledgingWallet = wallet
}