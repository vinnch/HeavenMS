/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package scripting.event;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import javax.script.ScriptException;

import net.server.world.MapleParty;
import net.server.world.MaplePartyCharacter;
import provider.MapleDataProviderFactory;
import server.TimerManager;
import server.expeditions.MapleExpedition;
import server.life.MapleMonster;
import server.maps.MapleMap;
import server.maps.MapleMapFactory;
import tools.DatabaseConnection;
import client.MapleCharacter;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Matze
 */
public class EventInstanceManager {
	private List<MapleCharacter> chars = new ArrayList<>();
	private List<MapleMonster> mobs = new LinkedList<>();
	private Map<MapleCharacter, Integer> killCount = new HashMap<>();
	private EventManager em;
	private MapleMapFactory mapFactory;
	private String name;
	private Properties props = new Properties();
	private long timeStarted = 0;
	private long eventTime = 0;
	private MapleExpedition expedition = null;
        private List<Integer> mapIds = new LinkedList<Integer>();
        private List<Boolean> isInstanced = new LinkedList<Boolean>();
        private final ReentrantReadWriteLock mutex = new ReentrantReadWriteLock();
        private final ReadLock rL = mutex.readLock();
        private final WriteLock wL = mutex.writeLock();
        private ScheduledFuture<?> event_schedule = null;
        private boolean disposed = false;

	public EventInstanceManager(EventManager em, String name) {
		this.em = em;
		this.name = name;
		mapFactory = new MapleMapFactory(MapleDataProviderFactory.getDataProvider(new File(System.getProperty("wzpath") + "/Map.wz")), MapleDataProviderFactory.getDataProvider(new File(System.getProperty("wzpath") + "/String.wz")), (byte) 0, (byte) 1);//Fk this
		mapFactory.setChannel(em.getChannelServer().getId());
	}

	public EventManager getEm() {
		return em;
	}

	public void registerPlayer(MapleCharacter chr) {
		if (chr == null || !chr.isLoggedin()){
			return;
		}
                
                try {
                        wL.lock();
                        try {
                                chars.add(chr);
                        }
                        finally {
                                wL.unlock();
                        }
                        
			chr.setEventInstance(this);
			em.getIv().invokeFunction("playerEntry", this, chr);
		} catch (ScriptException | NoSuchMethodException ex) {
			ex.printStackTrace();
		}
	}  
        
        public void exitPlayer(MapleCharacter chr) {
		if (chr == null || !chr.isLoggedin()){
			return;
		}
		try {
			unregisterPlayer(chr);
                        em.getIv().invokeFunction("playerExit", this, chr);
		} catch (ScriptException | NoSuchMethodException ex) {
			ex.printStackTrace();
		}
	}

	public void startEventTimer(long time) {
                timeStarted = System.currentTimeMillis();
		eventTime = time;
                
                event_schedule = TimerManager.getInstance().schedule(new Runnable() {
                    public void run() {
                        try {
                            em.getIv().invokeFunction("scheduledTimeout", EventInstanceManager.this);
                        } catch (ScriptException | NoSuchMethodException ex) {
                            Logger.getLogger(EventManager.class.getName()).log(Level.SEVERE, null, ex);
                        }
                    }
                }, time);
	}

	public boolean isTimerStarted() {
		return eventTime > 0 && timeStarted > 0;
	}

	public long getTimeLeft() {
		return eventTime - (System.currentTimeMillis() - timeStarted);
	}

	public void registerParty(MapleParty party, MapleMap map) {
		for (MaplePartyCharacter pc : party.getMembers()) {
			MapleCharacter c = map.getCharacterById(pc.getId());
			registerPlayer(c);
		}
	}

	public void registerExpedition(MapleExpedition exped) {
		expedition = exped;
		registerPlayer(exped.getLeader());
	}

	public void unregisterPlayer(MapleCharacter chr) {
                wL.lock();
                try {
                    chars.remove(chr);
                } finally {
                    wL.unlock();
                }
            
		chr.setEventInstance(null);
	}
	
	public int getPlayerCount() {
                rL.lock();
                try {
                    return chars.size();
                }
                finally {
                    rL.unlock();
                }
	}

	public List<MapleCharacter> getPlayers() {
                rL.lock();
                try {
                    return new ArrayList<>(chars);
                }
                finally {
                    rL.unlock();
                }
	}

	public void registerMonster(MapleMonster mob) {
		if (!mob.getStats().isFriendly()) { //We cannot register moon bunny
			mobs.add(mob);
			mob.setEventInstance(this);
		}
	}

	public void movePlayer(MapleCharacter chr) {
		try {
			em.getIv().invokeFunction("moveMap", this, chr);
		} catch (ScriptException | NoSuchMethodException ex) {
			ex.printStackTrace();
		}
	}
	
	public void monsterKilled(MapleMonster mob) {
		mobs.remove(mob);
                try {
                        em.getIv().invokeFunction("monsterKilled", mob, this);
                } catch (ScriptException | NoSuchMethodException ex) {
                        ex.printStackTrace();
                }
		if (mobs.isEmpty()) {
			try {
				em.getIv().invokeFunction("allMonstersDead", this);
			} catch (ScriptException | NoSuchMethodException ex) {
				ex.printStackTrace();
			}
		}
	}

	public void playerKilled(MapleCharacter chr) {
		try {
			em.getIv().invokeFunction("playerDead", this, chr);
		} catch (ScriptException | NoSuchMethodException ex) {
			ex.printStackTrace();
		}
	}

	public boolean revivePlayer(MapleCharacter chr) {
		try {
			Object b = em.getIv().invokeFunction("playerRevive", this, chr);
			if (b instanceof Boolean) {
				return (Boolean) b;
			}
		} catch (ScriptException | NoSuchMethodException ex) {
			ex.printStackTrace();
		}
		return true;
	}

	public void playerDisconnected(MapleCharacter chr) {
		try {
			em.getIv().invokeFunction("playerDisconnected", this, chr);
		} catch (ScriptException | NoSuchMethodException ex) {
			ex.printStackTrace();
		}
	}

	/**
	 *
	 * @param chr
	 * @param mob
	 */
	public void monsterKilled(MapleCharacter chr, MapleMonster mob) {
		try {
			Integer kc = killCount.get(chr);
			int inc = ((Double) em.getIv().invokeFunction("monsterValue", this, mob.getId())).intValue();
			if (kc == null) {
				kc = inc;
			} else {
				kc += inc;
			}
			killCount.put(chr, kc);
			if (expedition != null){
				expedition.monsterKilled(chr, mob);
			}
		} catch (ScriptException | NoSuchMethodException ex) {
			ex.printStackTrace();
		}
	}

	public int getKillCount(MapleCharacter chr) {
		Integer kc = killCount.get(chr);
		if (kc == null) {
			return 0;
		} else {
			return kc;
		}
	}

	public void dispose() {
            try {
                em.getIv().invokeFunction("dispose", this);
            } catch (ScriptException | NoSuchMethodException ex) {
                ex.printStackTrace();
            }

            wL.lock();
            try {
                chars.clear();
            } finally {
                wL.unlock();
            }
            
            event_schedule.cancel(true);

            mobs.clear();
            killCount.clear();
            mapFactory = null;
            if (expedition != null) {
                    em.getChannelServer().getExpeditions().remove(expedition);
            }
            em.disposeInstance(name);
            em = null;
	}

	public MapleMapFactory getMapFactory() {
		return mapFactory;
	}

	public void schedule(final String methodName, long delay) {
		TimerManager.getInstance().schedule(new Runnable() {
			@Override
			public void run() {
				try {
					em.getIv().invokeFunction(methodName, EventInstanceManager.this);
				} catch (ScriptException | NoSuchMethodException ex) {
					ex.printStackTrace();
				}
			}
		}, delay);
	}

	public String getName() {
		return name;
	}

	public void saveWinner(MapleCharacter chr) {
		try {
			try (PreparedStatement ps = DatabaseConnection.getConnection().prepareStatement("INSERT INTO eventstats (event, instance, characterid, channel) VALUES (?, ?, ?, ?)")) {
				ps.setString(1, em.getName());
				ps.setString(2, getName());
				ps.setInt(3, chr.getId());
				ps.setInt(4, chr.getClient().getChannel());
				ps.executeUpdate();
			}
		} catch (SQLException ex) {
			ex.printStackTrace();
		}
	}

	public MapleMap getMapInstance(int mapId) {
		MapleMap map = mapFactory.getMap(mapId);

		if (!mapFactory.isMapLoaded(mapId)) {
			if (em.getProperty("shuffleReactors") != null && em.getProperty("shuffleReactors").equals("true")) {
				map.shuffleReactors();
			}
		}
		return map;
	}

	public void setProperty(String key, String value) {
		props.setProperty(key, value);
	}

	public Object setProperty(String key, String value, boolean prev) {
		return props.setProperty(key, value);
	}

	public String getProperty(String key) {
		return props.getProperty(key);
	}

    public Properties getProperties(){
    	return props;
    }
	
	public void leftParty(MapleCharacter chr) {
		try {
			em.getIv().invokeFunction("leftParty", this, chr);
		} catch (ScriptException | NoSuchMethodException ex) {
			ex.printStackTrace();
		}
	}

	public void disbandParty() {
		try {
			em.getIv().invokeFunction("disbandParty", this);
		} catch (ScriptException | NoSuchMethodException ex) {
			ex.printStackTrace();
		}
	}

	public void finishPQ() {
		try {
			em.getIv().invokeFunction("clearPQ", this);
		} catch (ScriptException | NoSuchMethodException ex) {
			ex.printStackTrace();
		}
	}

	public void removePlayer(MapleCharacter chr) {
		try {
			em.getIv().invokeFunction("playerExit", this, chr);
		} catch (ScriptException | NoSuchMethodException ex) {
			ex.printStackTrace();
		}
	}

	public boolean isLeader(MapleCharacter chr) {
		return (chr.getParty().getLeader().getId() == chr.getId());
	}
        
        public final MapleMap setInstanceMap(final int mapid) { //gets instance map from the channelserv
            if (disposed) {
                return this.getMapFactory().getMap(mapid);
            }
            mapIds.add(mapid);
            isInstanced.add(false);
            return this.getMapFactory().getMap(mapid);
        }
        
        public final boolean disposeIfPlayerBelow(final byte size, final int towarp) {
            if (disposed) {
                return true;
            }
            MapleMap map = null;
            if (towarp > 0) {
                map = this.getMapFactory().getMap(towarp);
            }

            rL.lock();
            try {
                if (chars != null && chars.size() <= size) {
                    for (MapleCharacter chr : chars) {
                        if (chr == null) {
                            continue;
                        }
                        unregisterPlayer(chr);
                        if (towarp > 0) {
                            chr.changeMap(map, map.getPortal(0));
                        }
                    }
                    dispose();
                    return true;
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            } finally {
                rL.unlock();
            }
            return false;
        }
}
