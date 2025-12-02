package network.p2p;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import com.google.gson.Gson;

public class PeerDiscoveryService {
	 private static final PeerDiscoveryService instance = new PeerDiscoveryService();

    // Map: userId -> PeerInfo
    private final Map<Integer, PeerInfo> activePeers = new ConcurrentHashMap<>();
    // Map: localUserId -> Set(friendUserIds)
    private final Map<Integer, Set<Integer>> peerSubscriptions = new ConcurrentHashMap<>();
    
    private final Gson gson = new Gson();

    private PeerDiscoveryService() {}

    public static PeerDiscoveryService getInstance() {
        return instance;
    }

    private static class SignalingMessage {
        public String message;
        public PeerInfo peer;
        public PeerInfo[] peers;
    }
    
    public static class PeerUpdateResult {
        public final List<PeerInfo> added = new ArrayList<>();
        public final List<PeerInfo> updated = new ArrayList<>();
        public final List<PeerInfo> removed = new ArrayList<>();
        public boolean isEmpty() {
            return added.isEmpty() && updated.isEmpty() && removed.isEmpty();
        }
    }

    /**
     * Process JSON payload from server.
     * - Full list: JSON array, or object with "peers".
     * - Incremental: { message: "addNewPeer" | "removePeer", peer: { ... } }
     */
     public PeerUpdateResult processServerMessage(String json, Integer localUserId) {
        PeerUpdateResult out = new PeerUpdateResult();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            String t = json.trim();
            if (t.startsWith("[")) {
                PeerInfo[] arr = gson.fromJson(t, PeerInfo[].class);
                syncFullList(Arrays.asList(arr), out);
                return out;
            }
            SignalingMessage msg = gson.fromJson(t, SignalingMessage.class);
            if (msg == null) return out;

            if (msg.peers != null && msg.peers.length > 0) {
                syncFullList(Arrays.asList(msg.peers), out);
                return out;
            }

            if (msg.message != null) {
                String m = msg.message.toLowerCase().trim();
                if ("addnewpeer".equals(m) || "addpeer".equals(m)) {
                    if (msg.peer != null) {
                        boolean added = addPeer(msg.peer);
                        if (added) out.added.add(msg.peer);
                        else out.updated.add(msg.peer);
                    }
                } else if ("removepeer".equals(m) || "logout".equals(m)) {
                    if (msg.peer != null) {
                        PeerInfo removed = removePeer(msg.peer.getUserId());
                        if (removed != null) out.removed.add(removed);
                    }
                }
            }
        } catch (Exception e) {
            // parse error: caller may log
        }
        return out;
    }

    private void syncFullList(Collection<PeerInfo> newPeers, PeerUpdateResult out) {
        Map<Integer, PeerInfo> newMap = new HashMap<>();
        for (PeerInfo p : newPeers) {
            if (p != null && p.getUserId() != null) newMap.put(p.getUserId(), p);
        }

        // removed
        Set<Integer> removedCandidates = new HashSet<>();
        for (Integer id : activePeers.keySet()) {
            if (!newMap.containsKey(id)) removedCandidates.add(id);
        }
        for (Integer id : removedCandidates) {
            PeerInfo removed = activePeers.remove(id);
            if (removed != null) out.removed.add(removed);
        }
        // added/updated
        for (Map.Entry<Integer, PeerInfo> e : newMap.entrySet()) {
            Integer id = e.getKey();
            PeerInfo newPeer = e.getValue();
            PeerInfo old = activePeers.put(id, newPeer);
            if (old == null) out.added.add(newPeer);
            else if (!newPeer.equals(old)) out.updated.add(newPeer);
        }
    }

    /**
     * Directly add or update a peer in internal map.
     * Returns true if added; false if updated.
     */
    public boolean addPeer(PeerInfo peer) {
        if (peer == null || peer.getUserId() == null) return false;
        PeerInfo old = activePeers.put(peer.getUserId(), peer);
        return old == null;
    }

    /**
     * Remove a peer by id.
     */
    public PeerInfo removePeer(Integer id) {
        if (id == null) return null;
        return activePeers.remove(id);
    }

    public PeerInfo getPeer(Integer id) {
        if (id == null) return null;
        return activePeers.get(id);
    }

    public List<PeerInfo> getAllPeers() {
        return new ArrayList<>(activePeers.values());
    }

    /**
     * Set friend subscriptions for a local user so getOnlinePeersForUser returns only friends.
     */
    public void setSubscriptions(Integer userId, Collection<Integer> friendIds) {
        if (userId == null) return;
        Set<Integer> set = new CopyOnWriteArraySet<>();
        if (friendIds != null) set.addAll(friendIds);
        peerSubscriptions.put(userId, set);
    }

    public Set<Integer> getSubscriptions(Integer userId) {
        return peerSubscriptions.getOrDefault(userId, Collections.emptySet());
    }

    /**
     * Return only peers that are in the user's friend list. If user has no subscription, return all online peers.
     */
    public List<PeerInfo> getOnlinePeersForUser(Integer userId) {
        Set<Integer> subs = peerSubscriptions.get(userId);
        if (subs == null || subs.isEmpty()) return getAllPeers();
        List<PeerInfo> out = new ArrayList<>();
        for (Integer fid : subs) {
            PeerInfo p = activePeers.get(fid);
            if (p != null) out.add(p);
        }
        return out;
    }

    public void clearAll() {
        activePeers.clear();
        peerSubscriptions.clear();
    }
}
