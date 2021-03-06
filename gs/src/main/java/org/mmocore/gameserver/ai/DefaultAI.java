package org.mmocore.gameserver.ai;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.mmocore.commons.collections.LazyArrayList;
import org.mmocore.commons.lang.reference.HardReference;
import org.mmocore.commons.math.random.RndSelector;
import org.mmocore.commons.threading.RunnableImpl;
import org.mmocore.commons.util.Rnd;
import org.mmocore.gameserver.Config;
import org.mmocore.gameserver.ThreadPoolManager;
import org.mmocore.gameserver.data.xml.holder.NpcHolder;
import org.mmocore.gameserver.geodata.GeoEngine;
import org.mmocore.gameserver.instancemanager.ReflectionManager;
import org.mmocore.gameserver.model.AggroList.AggroInfo;
import org.mmocore.gameserver.model.Creature;
import org.mmocore.gameserver.model.MinionList;
import org.mmocore.gameserver.model.Playable;
import org.mmocore.gameserver.model.Player;
import org.mmocore.gameserver.model.Skill;
import org.mmocore.gameserver.model.Skill.SkillType;
import org.mmocore.gameserver.model.World;
import org.mmocore.gameserver.model.WorldRegion;
import org.mmocore.gameserver.model.Zone.ZoneType;
import org.mmocore.gameserver.model.base.SpecialEffectState;
import org.mmocore.gameserver.model.entity.SevenSigns;
import org.mmocore.gameserver.model.instances.MonsterInstance;
import org.mmocore.gameserver.model.instances.NpcInstance;
import org.mmocore.gameserver.model.instances.RaidBossInstance;
import org.mmocore.gameserver.model.quest.QuestEventType;
import org.mmocore.gameserver.model.quest.QuestState;
import org.mmocore.gameserver.network.l2.s2c.MagicSkillUse;
import org.mmocore.gameserver.network.l2.s2c.StatusUpdate;
import org.mmocore.gameserver.skills.SkillEntry;
import org.mmocore.gameserver.stats.Stats;
import org.mmocore.gameserver.tables.SkillTable;
import org.mmocore.gameserver.taskmanager.AiTaskManager;
import org.mmocore.gameserver.utils.Location;
import org.mmocore.gameserver.utils.Log;
import org.mmocore.gameserver.utils.NpcUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultAI extends CharacterAI
{
	protected static final Logger _log = LoggerFactory.getLogger(DefaultAI.class);

	public static enum TaskType
	{
		MOVE,
		ATTACK,
		CAST,
		BUFF
	}

	public static final int TaskDefaultWeight = 10000;

	public static class Task
	{
		public TaskType type;
		public SkillEntry skill;
		public HardReference<? extends Creature> target;
		public Location loc;
		public boolean pathfind;
		public int weight = TaskDefaultWeight;
	}

	public void addTaskCast(Creature target, SkillEntry skill)
	{
		Task task = new Task();
		task.type = TaskType.CAST;
		task.target = target.getRef();
		task.skill = skill;
		_tasks.add(task);
		_def_think = true;
	}

	public void addTaskBuff(Creature target, SkillEntry skill)
	{
		Task task = new Task();
		task.type = TaskType.BUFF;
		task.target = target.getRef();
		task.skill = skill;
		_tasks.add(task);
		_def_think = true;
	}

	public void addTaskAttack(Creature target)
	{
		Task task = new Task();
		task.type = TaskType.ATTACK;
		task.target = target.getRef();
		_tasks.add(task);
		_def_think = true;
	}

	public void addTaskAttack(Creature target, SkillEntry skill, int weight)
	{
		Task task = new Task();
		task.type = skill.getTemplate().isOffensive() ? TaskType.CAST : TaskType.BUFF;
		task.target = target.getRef();
		task.skill = skill;
		task.weight = weight;
		_tasks.add(task);
		_def_think = true;
	}

	public void addTaskMove(Location loc, boolean pathfind)
	{
		Task task = new Task();
		task.type = TaskType.MOVE;
		task.loc = loc;
		task.pathfind = pathfind;
		_tasks.add(task);
		_def_think = true;
	}

	protected void addTaskMove(int locX, int locY, int locZ, boolean pathfind)
	{
		addTaskMove(new Location(locX, locY, locZ), pathfind);
	}

	private static class TaskComparator implements Comparator<Task>
	{
		private static final Comparator<Task> instance = new TaskComparator();

		public static final Comparator<Task> getInstance()
		{
			return instance;
		}

		@Override
		public int compare(Task o1, Task o2)
		{
			if(o1 == null || o2 == null)
				return 0;
			return o2.weight - o1.weight;
		}
	}

	protected class Teleport extends RunnableImpl
	{
		Location _destination;

		public Teleport(Location destination)
		{
			_destination = destination;
		}

		@Override
		public void runImpl() throws Exception
		{
			NpcInstance actor = getActor();
			actor.teleToLocation(_destination);
		}
	}

	protected class RunningTask extends RunnableImpl
	{
		@Override
		public void runImpl() throws Exception
		{
			NpcInstance actor = getActor();
			actor.setRunning();
			_runningTask = null;
		}
	}

	public class MadnessTask extends RunnableImpl
	{
		@Override
		public void runImpl() throws Exception
		{
			NpcInstance actor = getActor();
			actor.stopConfused();
			_madnessTask = null;
		}
	}

	protected long AI_TASK_ATTACK_DELAY = Config.AI_TASK_ATTACK_DELAY;
	protected long AI_TASK_ACTIVE_DELAY = Config.AI_TASK_ACTIVE_DELAY;
	protected long AI_TASK_DELAY_CURRENT = AI_TASK_ACTIVE_DELAY;
	protected int MAX_PURSUE_RANGE;

	protected ScheduledFuture<?> _aiTask;

	protected ScheduledFuture<?> _runningTask;
	protected ScheduledFuture<?> _madnessTask;

	/** The flag used to indicate that a thinking action is in progress */
	private Lock _thinking = new ReentrantLock();;
	/** Показывает, есть ли задания */
	protected boolean _def_think = false;

	/** The L2NpcInstance aggro counter */
	protected long _globalAggro;

	protected long _randomAnimationEnd;
	protected int _pathfindFails;

	/** Список заданий */
	protected final NavigableSet<Task> _tasks = new ConcurrentSkipListSet<Task>(TaskComparator.getInstance());

	protected final SkillEntry[] _damSkills, _dotSkills, _debuffSkills, _healSkills, _buffSkills, _stunSkills;

	protected long _lastActiveCheck;
	protected long _checkAggroTimestamp = 0;
	/** Время актуальности состояния атаки */
	protected long _attackTimeout;

	protected long _lastFactionNotifyTime = 0;
	protected long _minFactionNotifyInterval = 10000;

	protected boolean _isGlobal;

	private final SkillEntry UD_SKILL = SkillTable.getInstance().getSkillEntry(5044,3);
	private static final int UD_NONE = 0;
	private static final int UD_CAST = 1;
	private static final int UD_CASTED = 2;
	private static final int UD_DISTANCE = 150;
	private volatile int _UDFlag = UD_NONE;
	private int _UDRate;

	private boolean _isSearchingMaster;

	public DefaultAI(NpcInstance actor)
	{
		super(actor);

		setAttackTimeout(Long.MAX_VALUE);

		_damSkills = actor.getTemplate().getDamageSkills();
		_dotSkills = actor.getTemplate().getDotSkills();
		_debuffSkills = actor.getTemplate().getDebuffSkills();
		_buffSkills = actor.getTemplate().getBuffSkills();
		_stunSkills = actor.getTemplate().getStunSkills();
		_healSkills = actor.getTemplate().getHealSkills();

		// Preload some AI params
		MAX_PURSUE_RANGE = actor.getParameter("MaxPursueRange", actor.isRaid() ? Config.MAX_PURSUE_RANGE_RAID : actor.isUnderground() ? Config.MAX_PURSUE_UNDERGROUND_RANGE : Config.MAX_PURSUE_RANGE);
		_minFactionNotifyInterval = actor.getParameter("FactionNotifyInterval", 10000);
		_isGlobal = actor.getParameter("GlobalAI", false);
		_UDRate = actor.getParameter("LongRangeGuardRate", 0);
		_isSearchingMaster = actor.getParameter("searchingMaster", false);
	}

	@Override
	public void runImpl()
	{
		if(_aiTask == null)
			return;
		// проверяем, если NPC вышел в неактивный регион, отключаем AI
		if(!isGlobalAI() && System.currentTimeMillis() - _lastActiveCheck > 60000L)
		{
			_lastActiveCheck = System.currentTimeMillis();
			NpcInstance actor = getActor();
			WorldRegion region = actor == null ? null : actor.getCurrentRegion();
			if(region == null || !region.isActive())
			{
				stopAITask();
				return;
			}
		}
		onEvtThink();
	}

	@Override
	public final synchronized void startAITask()
	{
		if(_aiTask == null)
		{
			AI_TASK_DELAY_CURRENT = AI_TASK_ACTIVE_DELAY;
			_aiTask = AiTaskManager.getInstance().scheduleAtFixedRate(this, 0L, AI_TASK_DELAY_CURRENT);
		}
	}

	protected final synchronized void switchAITask(long NEW_DELAY)
	{
		if (_aiTask != null)
		{
			if (AI_TASK_DELAY_CURRENT == NEW_DELAY)
				return;
			_aiTask.cancel(false);
		}

		AI_TASK_DELAY_CURRENT = NEW_DELAY;
		_aiTask = AiTaskManager.getInstance().scheduleAtFixedRate(this, 0L, AI_TASK_DELAY_CURRENT);
	}

	@Override
	public final synchronized void stopAITask()
	{
		if(_aiTask != null)
		{
			_aiTask.cancel(false);
			_aiTask = null;
		}
	}

	@Override
	public boolean isGlobalAI()
	{
		return _isGlobal;
	}

	/**
	 * Определяет, может ли этот тип АИ видеть персонажей в режиме Silent Move.
	 * @param target L2Playable цель
	 * @return true если цель видна в режиме Silent Move
	 */
	protected boolean canSeeInSilentMove(Playable target)
	{
		if(getActor().getParameter("canSeeInSilentMove", false))
			return true;
		return !target.isSilentMoving();
	}

	protected boolean checkAggression(Creature target)
	{
		NpcInstance actor = getActor();
		if(getIntention() != CtrlIntention.AI_INTENTION_ACTIVE || !isGlobalAggro())
			return false;
		if(target.isAlikeDead())
			return false;

		if(target.isNpc() && target.isInvul())
			return false;

		if(target.isPlayable())
		{
			if(!canSeeInSilentMove((Playable) target))
				return false;
			if(actor.getFaction().getName().equalsIgnoreCase("varka_silenos_clan") && target.getPlayer().getVarka() > 0)
				return false;
			if(actor.getFaction().getName().equalsIgnoreCase("ketra_orc_clan") && target.getPlayer().getKetra() > 0)
				return false;
			/*if(target.isFollow && !target.isPlayer() && target.getFollowTarget() != null && target.getFollowTarget().isPlayer())
					return;*/
			if(target.isPlayer() && target.getInvisible() == SpecialEffectState.GM)
				return false;
			if(((Playable) target).getNonAggroTime() > System.currentTimeMillis())
				return false;
			if(target.isPlayer() && !target.getPlayer().isActive())
				return false;
			if(actor.isMonster() && target.isInZonePeace())
				return false;
		}

		if (!isInAggroRange(target))
			return false;
		if(!canAttackCharacter(target))
			return false;
		if(!GeoEngine.canSeeTarget(actor, target, false))
			return false;

		return true;
	}

	protected boolean isInAggroRange(Creature target)
	{
		NpcInstance actor = getActor();
		AggroInfo ai = actor.getAggroList().get(target);
		if(ai != null && ai.hate > 0)
		{
			if(!target.isInRangeZ(actor.getSpawnedLoc(), MAX_PURSUE_RANGE))
				return false;
		}
		else if(!isAggressive() || !target.isInRangeZ(actor.getSpawnedLoc(), actor.getAggroRange()))
			return false;

		return true;
	}

	protected void setIsInRandomAnimation(long time)
	{
		_randomAnimationEnd = System.currentTimeMillis() + time;
	}

	protected boolean randomAnimation()
	{
		NpcInstance actor = getActor();

		if(actor.getParameter("noRandomAnimation", false))
			return false;

		if(actor.hasRandomAnimation() && !actor.isActionsDisabled() && !actor.isMoving && !actor.isInCombat() && Rnd.chance(Config.RND_ANIMATION_RATE))
		{
			setIsInRandomAnimation(3000);
			actor.onRandomAnimation();
			return true;
		}
		return false;
	}

	protected boolean randomWalk()
	{
		NpcInstance actor = getActor();

		if(actor.getParameter("noRandomWalk", false))
			return false;

		return !actor.isMoving && maybeMoveToHome();
	}

	protected Creature getNearestTarget(List<Creature> targets)
	{
		NpcInstance actor = getActor();

		Creature nextTarget = null;
		long minDist = Long.MAX_VALUE;

		Creature target;
		for(int i = 0; i < targets.size(); i++)
		{
			target = targets.get(i);
			long dist = actor.getXYZDeltaSq(target.getX(), target.getY(), target.getZ());
			if(dist < minDist)
				nextTarget = target;
		}

		return nextTarget;
	}

	/**
	 * @return true если действие выполнено, false если нет
	 */
	protected boolean thinkActive()
	{
		NpcInstance actor = getActor();
		if(actor.isActionsDisabled())
			return true;

		if(_randomAnimationEnd > System.currentTimeMillis())
			return true;

		if(_def_think)
		{
			if(doTask())
				clearTasks();
			return true;
		}

		long now = System.currentTimeMillis();
		if(now - _checkAggroTimestamp > Config.AGGRO_CHECK_INTERVAL)
		{
			_checkAggroTimestamp = now;

			boolean aggressive = Rnd.chance(actor.getParameter("SelfAggressive", isAggressive() ? 100 : 0));
			if(!actor.getAggroList().isEmpty() || aggressive)
			{
				int count = 0;
				List<Creature> targets = World.getAroundCharacters(actor);
				while(!targets.isEmpty())
				{
					count++;
					if (count > 1000)
					{
						Log.debug("AI loop count exceeded, "+getActor()+" "+getActor().getLoc()+" "+targets);
						return false;
					}

					Creature target = getNearestTarget(targets);
					if(target == null)
						break;

					if(aggressive || actor.getAggroList().get(target) != null)
						if(checkAggression(target))
						{
							actor.getAggroList().addDamageHate(target, 0, 2);

							if(target.isServitor())
								actor.getAggroList().addDamageHate(target.getPlayer(), 0, 1);

							startRunningTask(AI_TASK_ATTACK_DELAY);
							setIntention(CtrlIntention.AI_INTENTION_ATTACK, target);

							return true;
						}

					targets.remove(target);
				}
			}
		}

		if(actor.isMinion())
		{
			NpcInstance leader = actor.getLeader();
			if(leader != null)
			{
				double distance = actor.getDistance(leader.getX(), leader.getY());
				if(distance > 1000)
				{
					actor.teleToLocation(leader.getRndMinionPosition());
					return true;
				}
				else if(distance > 200)
				{
					addTaskMove(leader.getRndMinionPosition(), false);
					return true;
				}
			}
		}

		if(randomAnimation())
			return true;

		if(randomWalk())
			return true;

		return false;
	}

	@Override
	protected void onIntentionIdle()
	{
		NpcInstance actor = getActor();

		// Удаляем все задания
		clearTasks();

		actor.stopMove();
		actor.getAggroList().clear(true);
		setAttackTimeout(Long.MAX_VALUE);
		setAttackTarget(null);

		changeIntention(CtrlIntention.AI_INTENTION_IDLE, null, null);
	}

	@Override
	protected void onIntentionActive()
	{
		NpcInstance actor = getActor();

		actor.stopMove();
		setAttackTimeout(Long.MAX_VALUE);

		if(getIntention() != CtrlIntention.AI_INTENTION_ACTIVE)
		{
			switchAITask(AI_TASK_ACTIVE_DELAY);
			changeIntention(CtrlIntention.AI_INTENTION_ACTIVE, null, null);
		}

		onEvtThink();
	}

	@Override
	protected void onIntentionAttack(Creature target)
	{
		NpcInstance actor = getActor();

		// Удаляем все задания
		clearTasks();

		actor.stopMove();
		setAttackTarget(target);
		setAttackTimeout(getMaxAttackTimeout() + System.currentTimeMillis());
		setGlobalAggro(0);

		if(getIntention() != CtrlIntention.AI_INTENTION_ATTACK)
		{
			changeIntention(CtrlIntention.AI_INTENTION_ATTACK, target, null);
			switchAITask(AI_TASK_ATTACK_DELAY);
		}

		onEvtThink();
	}

	protected boolean canAttackCharacter(Creature target)
	{
		return target.isPlayable();
	}

	protected boolean isAggressive()
	{
		return getActor().isAggressive();
	}

	protected boolean checkTarget(Creature target, int range)
	{
		NpcInstance actor = getActor();
		if(target == null || target.isAlikeDead() || !actor.isInRangeZ(target, range))
			return false;

		// если не видим чаров в хайде - не атакуем их
		final boolean hidden = target.isPlayable() && target.isInvisible();

		if(!hidden && actor.isConfused())
			return true;

		//В состоянии атаки атакуем всех, на кого у нас есть хейт
		if(getIntention() == CtrlIntention.AI_INTENTION_ATTACK)
		{
			AggroInfo ai = actor.getAggroList().get(target);
			if (ai != null)
			{
				if (hidden)
				{
					ai.hate = 0; // очищаем хейт
					return false;
				}
				return ai.hate > 0;
			}
			return false;
		}

		return canAttackCharacter(target);
	}

	public void setAttackTimeout(long time)
	{
		_attackTimeout = time;
	}

	protected long getAttackTimeout()
	{
		return _attackTimeout;
	}

	protected void thinkAttack()
	{
		NpcInstance actor = getActor();
		if(actor.isDead())
			return;

		Location loc = actor.getSpawnedLoc();
		if(!actor.isInRange(loc, MAX_PURSUE_RANGE))
		{
			teleportHome();
			return;
		}

		if(doTask() && !actor.isAttackingNow() && !actor.isCastingNow())
		{
			if(!createNewTask())
			{
				if(System.currentTimeMillis() > getAttackTimeout())
					returnHome();
			}
		}
	}

	@Override
	protected void onEvtSpawn()
	{
		setGlobalAggro(System.currentTimeMillis() + getActor().getParameter("globalAggro", 10000L));

		_UDFlag = UD_NONE;

		if (getActor().isMinion() && getActor().getLeader() != null)
			_isGlobal = getActor().getLeader().getAI().isGlobalAI();
	}

	@Override
	protected void onEvtReadyToAct()
	{
		onEvtThink();
	}

	@Override
	protected void onEvtArrivedTarget()
	{
		onEvtThink();
	}

	@Override
	protected void onEvtArrived()
	{
		onEvtThink();
	}

	protected boolean tryMoveToTarget(Creature target)
	{
		return tryMoveToTarget(target, 0);
	}

	protected boolean tryMoveToTarget(Creature target, int range)
	{
		NpcInstance actor = getActor();

		if(!actor.followToCharacter(target, actor.getPhysicalAttackRange(), true))
			_pathfindFails++;

		if(_pathfindFails >= getMaxPathfindFails() && (System.currentTimeMillis() > getAttackTimeout() - getMaxAttackTimeout() + getTeleportTimeout()) && actor.isInRange(target, MAX_PURSUE_RANGE))
		{
			_pathfindFails = 0;

			if(target.isPlayable())
			{
				AggroInfo hate = actor.getAggroList().get(target);
				if(hate == null || hate.hate < 100 || (actor.getReflection() != ReflectionManager.DEFAULT && actor instanceof RaidBossInstance)) // bless freya
				{
					returnHome();
					return false;
				}
			}
			Location loc = GeoEngine.moveCheckForAI(target.getLoc(), actor.getLoc(), actor.getGeoIndex());
			if(!GeoEngine.canMoveToCoord(actor.getX(), actor.getY(), actor.getZ(), loc.x, loc.y, loc.z, actor.getGeoIndex())) // Для подстраховки
				loc = target.getLoc();
			actor.teleToLocation(loc);
		}

		return true;
	}

	protected boolean maybeNextTask(Task currentTask)
	{
		// Следующее задание
		_tasks.remove(currentTask);
		// Если заданий больше нет - определить новое
		if(_tasks.size() == 0)
			return true;
		return false;
	}

	protected boolean doTask()
	{
		NpcInstance actor = getActor();

		if(!_def_think)
			return true;

		Task currentTask = _tasks.pollFirst();
		if(currentTask == null)
		{
			clearTasks();
			return true;
		}

		if(actor.isDead() || actor.isAttackingNow() || actor.isCastingNow())
			return false;

		switch(currentTask.type)
		{
			// Задание "прибежать в заданные координаты"
			case MOVE:
			{
				if(actor.isMovementDisabled() || !getIsMobile())
					return true;

				if(actor.isInRange(currentTask.loc, 100))
					return maybeNextTask(currentTask);

				if(actor.isMoving)
					return false;

				if(!actor.moveToLocation(currentTask.loc, 0, currentTask.pathfind))
				{
					clientStopMoving();
					_pathfindFails = 0;
					actor.teleToLocation(currentTask.loc);
					//actor.broadcastPacketToOthers(new MagicSkillUse(actor, actor, 2036, 1, 500, 600000));
					//ThreadPoolManager.getInstance().scheduleAi(new Teleport(currentTask.loc), 500, false);
					return maybeNextTask(currentTask);
				}
			}
				break;
			// Задание "добежать - ударить"
			case ATTACK:
			{
				Creature target = currentTask.target.get();

				if(!checkTarget(target, MAX_PURSUE_RANGE))
					return true;

				setAttackTarget(target);

				if(actor.isMoving)
					return Rnd.chance(25);

				if(actor.getRealDistance3D(target) <= actor.getPhysicalAttackRange() + 40 && GeoEngine.canSeeTarget(actor, target, false))
				{
					clientStopMoving();
					_pathfindFails = 0;
					setAttackTimeout(getMaxAttackTimeout() + System.currentTimeMillis());
					actor.doAttack(target);
					return maybeNextTask(currentTask);
				}

				if(actor.isMovementDisabled() || !getIsMobile())
					return true;

				tryMoveToTarget(target);
			}
				break;
			// Задание "добежать - атаковать скиллом"
			case CAST:
			{
				Creature target = currentTask.target.get();

				if(actor.isMuted(currentTask.skill) || actor.isSkillDisabled(currentTask.skill) || currentTask.skill.isDisabled())
					return true;

				Skill skill = currentTask.skill.getTemplate();
				boolean isAoE = skill.getTargetType() == Skill.SkillTargetType.TARGET_AURA;
				int castRange = skill.getAOECastRange();

				if(!checkTarget(target, MAX_PURSUE_RANGE + castRange))
					return true;

				setAttackTarget(target);

				if(actor.getRealDistance3D(target) <= castRange + 60 && GeoEngine.canSeeTarget(actor, target, false))
				{
					clientStopMoving();
					_pathfindFails = 0;
					setAttackTimeout(getMaxAttackTimeout() + System.currentTimeMillis());
					actor.doCast(currentTask.skill, isAoE ? actor : target, !target.isPlayable());
					return maybeNextTask(currentTask);
				}

				if(actor.isMoving)
					return Rnd.chance(10);

				if(actor.isMovementDisabled() || !getIsMobile())
					return true;

				tryMoveToTarget(target, castRange);
			}
				break;
			// Задание "добежать - применить скилл"
			case BUFF:
			{
				Creature target = currentTask.target.get();

				if(actor.isMuted(currentTask.skill) || actor.isSkillDisabled(currentTask.skill) || currentTask.skill.isDisabled())
					return true;

				Skill skill = currentTask.skill.getTemplate();
				if (skill.getTargetType() == Skill.SkillTargetType.TARGET_SELF)
				{
					actor.doCast(currentTask.skill, actor, false);
					return maybeNextTask(currentTask);
				}

				if(target == null || target.isAlikeDead() || !actor.isInRange(target, 2000))
					return true;

				boolean isAoE = skill.getTargetType() == Skill.SkillTargetType.TARGET_AURA;
				int castRange = skill.getAOECastRange();

				if(actor.isMoving)
					return Rnd.chance(10);

				if(actor.getRealDistance3D(target) <= castRange + 60 && GeoEngine.canSeeTarget(actor, target, false))
				{
					clientStopMoving();
					_pathfindFails = 0;
					actor.doCast(currentTask.skill, isAoE ? actor : target, !target.isPlayable());
					return maybeNextTask(currentTask);
				}

				if(actor.isMovementDisabled() || !getIsMobile())
					return true;

				tryMoveToTarget(target);
			}
				break;
		}

		return false;
	}

	protected boolean createNewTask()
	{
		return false;
	}

	protected boolean defaultNewTask()
	{
		clearTasks();

		NpcInstance actor = getActor();
		Creature target;
		if(actor == null || (target = prepareTarget()) == null)
			return false;

		double distance = actor.getDistance(target);
		return chooseTaskAndTargets(null, target, distance);
	}

	@Override
	protected void onEvtThink()
	{
		NpcInstance actor = getActor();
		if(actor == null || actor.isActionsDisabled() || actor.isAfraid())
			return;

		if(_randomAnimationEnd > System.currentTimeMillis())
			return;

		if(actor.isRaid() && (actor.isInZonePeace() || actor.isInZoneBattle() || actor.isInZone(ZoneType.SIEGE)))
		{
			teleportHome();
			return;
		}

		if (!_thinking.tryLock())
			return;
		try
		{
			if(!Config.BLOCK_ACTIVE_TASKS && getIntention() == CtrlIntention.AI_INTENTION_ACTIVE)
				thinkActive();
			else if(getIntention() == CtrlIntention.AI_INTENTION_ATTACK)
				thinkAttack();
		}
		finally
		{
			_thinking.unlock();
		}
	}

	@Override
	protected void onEvtDead(Creature killer)
	{
		NpcInstance actor = getActor();

		int transformer = actor.getParameter("transformOnDead", 0);
		int chance = actor.getParameter("transformChance", 100);
		if(transformer > 0 && Rnd.chance(chance))
		{
			NpcInstance npc = NpcUtils.spawnSingle(transformer, actor.getLoc(), actor.getReflection()) ;

			if(killer != null && killer.isPlayable())
			{
				npc.getAI().notifyEvent(CtrlEvent.EVT_AGGRESSION, killer, 100);
				killer.setTarget(npc);
				killer.sendPacket(npc.makeStatusUpdate(StatusUpdate.CUR_HP, StatusUpdate.MAX_HP));
			}
		}

		super.onEvtDead(killer);
	}

	@Override
	protected void onEvtClanAttacked(Creature attacked, Creature attacker, int damage)
	{
		if(getIntention() != CtrlIntention.AI_INTENTION_ACTIVE || !isGlobalAggro())
			return;

		notifyEvent(CtrlEvent.EVT_AGGRESSION, attacker, 2);
	}

	@Override
	protected void onEvtAttacked(Creature attacker, SkillEntry skill, int damage)
	{
		NpcInstance actor = getActor();
		if(attacker == null || actor.isDead())
			return;

		int transformer = actor.getParameter("transformOnUnderAttack", 0);
		if(transformer > 0)
		{
			int chance = actor.getParameter("transformChance", 5);
			if(chance == 100 || ((MonsterInstance) actor).getChampion() == 0 && actor.getCurrentHpPercents() > 50 && Rnd.chance(chance))
			{
				MonsterInstance npc = (MonsterInstance) NpcHolder.getInstance().getTemplate(transformer).getNewInstance();
				npc.setSpawnedLoc(actor.getLoc());
				npc.setReflection(actor.getReflection());
				npc.setChampion(((MonsterInstance) actor).getChampion());
				npc.setCurrentHpMp(npc.getMaxHp(), npc.getMaxMp(), true);
				npc.spawnMe(npc.getSpawnedLoc());
				npc.getAI().notifyEvent(CtrlEvent.EVT_AGGRESSION, attacker, 100);
				actor.doDie(actor);
				actor.decayMe();
				attacker.setTarget(npc);
				attacker.sendPacket(npc.makeStatusUpdate(StatusUpdate.CUR_HP, StatusUpdate.MAX_HP));
				return;
			}
		}

		Player player = attacker.getPlayer();

		if(player != null)
		{
			//FIXME [G1ta0] затычка для 7 печатей, при атаке монстра 7 печатей телепортирует персонажа в ближайший город
			if((SevenSigns.getInstance().isSealValidationPeriod() || SevenSigns.getInstance().isCompResultsPeriod()) && actor.isSevenSignsMonster())
			{
				int pcabal = SevenSigns.getInstance().getPlayerCabal(player);
				int wcabal = SevenSigns.getInstance().getCabalHighestScore();
				if(pcabal != wcabal && wcabal != SevenSigns.CABAL_NULL)
				{
					player.sendMessage("You have been teleported to the nearest town because you not signed for winning cabal.");
					player.teleToClosestTown();
					return;
				}
			}

			List<QuestState> quests = player.getQuestsForEvent(actor, QuestEventType.ATTACKED_WITH_QUEST);
			if(quests != null)
				for(QuestState qs : quests)
					qs.getQuest().notifyAttack(actor, qs);
		}

		Creature myTarget = attacker;
		if (damage > 0 && attacker.isServitor())
		{
			final Player summoner = attacker.getPlayer();
			if (summoner != null)
				if (_isSearchingMaster) // моб ищет и атакует хозяина саммона
					myTarget = summoner;
				else // Обычно 1 хейт добавляется хозяину суммона, чтобы после смерти суммона моб накинулся на хозяина.
					actor.getAggroList().addDamageHate(summoner, 0, 1);
		}

		//Добавляем только хейт, урон, если атакующий - игровой персонаж, будет добавлен в L2NpcInstance.onReduceCurrentHp
		actor.getAggroList().addDamageHate(myTarget, 0, damage);

		if(getIntention() != CtrlIntention.AI_INTENTION_ATTACK)
		{
			if(!actor.isRunning())
				startRunningTask(AI_TASK_ATTACK_DELAY);
			setIntention(CtrlIntention.AI_INTENTION_ATTACK, myTarget);
		}

		notifyFriends(attacker, skill, damage);
		if (damage > 0)
			checkUD(attacker, skill);
	}

	@Override
	protected void onEvtAggression(Creature attacker, int aggro)
	{
		NpcInstance actor = getActor();
		if(attacker == null || actor.isDead())
			return;

		Creature myTarget = attacker;
		if (aggro > 0 && attacker.isServitor())
		{
			final Player summoner = attacker.getPlayer();
			if (summoner != null)
				if (_isSearchingMaster) // моб ищет и атакует хозяина саммона
					myTarget = summoner;
				else // Обычно 1 хейт добавляется хозяину суммона, чтобы после смерти суммона моб накинулся на хозяина.
					actor.getAggroList().addDamageHate(summoner, 0, 1);
		}

		//Добавляем только хейт, урон, если атакующий - игровой персонаж, будет добавлен в L2NpcInstance.onReduceCurrentHp
		actor.getAggroList().addDamageHate(myTarget, 0, aggro);

		if(getIntention() != CtrlIntention.AI_INTENTION_ATTACK)
		{
			startRunningTask(AI_TASK_ATTACK_DELAY);
			setIntention(CtrlIntention.AI_INTENTION_ATTACK, myTarget);
		}
	}

	@Override
	protected void onEvtFinishCasting(SkillEntry skill)
	{
		if (skill == UD_SKILL)
			_UDFlag = UD_CASTED;
	}

	protected boolean maybeMoveToHome()
	{
		NpcInstance actor = getActor();
		if(actor.isDead())
			return false;

		boolean randomWalk = actor.hasRandomWalk();
		Location sloc = actor.getSpawnedLoc();

		// Random walk or not?
		if(randomWalk && (!Config.RND_WALK || !Rnd.chance(Config.RND_WALK_RATE)))
			return false;

		boolean isInRange = actor.isInRangeZ(sloc, Config.MAX_DRIFT_RANGE);

		if(!randomWalk && isInRange)
			return false;

		Location pos = Location.findPointToStay(actor, sloc, 0, Config.MAX_DRIFT_RANGE);

		actor.setWalking();

		// Телепортируемся домой, только если далеко от дома
		if(!actor.moveToLocation(pos, 0, true) && !isInRange)
			teleportHome();

		return true;
	}

	protected void returnHome()
	{
		returnHome(true, Config.ALWAYS_TELEPORT_HOME);
	}

	protected void teleportHome()
	{
		returnHome(true, true);
	}

	protected void returnHome(boolean clearAggro, boolean teleport)
	{
		NpcInstance actor = getActor();
		Location sloc = actor.getSpawnedLoc();

		// Удаляем все задания
		clearTasks();
		actor.stopMove();

		if(clearAggro)
			actor.getAggroList().clear(true);

		setAttackTimeout(Long.MAX_VALUE);
		setAttackTarget(null);

		changeIntention(CtrlIntention.AI_INTENTION_ACTIVE, null, null);

		if(teleport)
		{
			actor.broadcastPacketToOthers(new MagicSkillUse(actor, actor, 2036, 1, 500, 0));
			actor.teleToLocation(sloc.x, sloc.y, GeoEngine.getHeight(sloc, actor.getGeoIndex()));
		}
		else
		{
			if(!clearAggro)
				actor.setRunning();
			else
				actor.setWalking();

			addTaskMove(sloc, false);
		}
	}

	protected void checkUD(Creature attacker, SkillEntry skill)
	{
		if (_UDRate == 0 || (skill != null && skill.getTemplate().isUDSafe()))
			return;

		if (getActor().getDistance(attacker) > UD_DISTANCE)
		{
			if (_UDFlag == UD_NONE || _UDFlag == UD_CASTED)
				if (Rnd.chance(_UDRate) && canUseSkill(UD_SKILL, getActor(), 0))
					_UDFlag = UD_CAST;
		}
		else
		{
			if (_UDFlag == UD_CASTED || _UDFlag == UD_CAST)
			{
				getActor().getEffectList().stopEffect(UD_SKILL);
				_UDFlag = UD_NONE;
			}
		}
	}

	protected boolean applyUD()
	{
		if (_UDRate == 0 || _UDFlag == UD_NONE)
			return false;

		if (_UDFlag == UD_CAST)
		{
			addTaskBuff(getActor(), UD_SKILL);
			return true;
		}
		return false;
	}

	protected Creature prepareTarget()
	{
		NpcInstance actor = getActor();

		if(actor.isConfused())
			return getAttackTarget();

		// Для "двинутых" боссов, иногда, выбираем случайную цель
		if(Rnd.chance(actor.getParameter("isMadness", 0)))
		{
			Creature randomHated = actor.getAggroList().getRandomHated();
			if(randomHated != null && Math.abs(actor.getZ() - randomHated.getZ()) < 1000) // Не прыгаем к случайной цели если слишком большая разница Z.
			{
				setAttackTarget(randomHated);
				if(_madnessTask == null && !actor.isConfused())
				{
					actor.startConfused();
					_madnessTask = ThreadPoolManager.getInstance().schedule(new MadnessTask(), 10000);
				}
				return randomHated;
			}
		}

		// Новая цель исходя из агрессивности
		List<Creature> hateList = actor.getAggroList().getHateList(MAX_PURSUE_RANGE);
		Creature hated = null;
		for(Creature cha : hateList)
		{
			//Не подходит, очищаем хейт
			if(!checkTarget(cha, MAX_PURSUE_RANGE))
			{
				actor.getAggroList().remove(cha, true);
				continue;
			}
			hated = cha;
			break;
		}

		if(hated != null)
		{
			setAttackTarget(hated);
			return hated;
		}

		return null;
	}

	protected boolean canUseSkill(SkillEntry skill, Creature target, double distance)
	{
		NpcInstance actor = getActor();
		if(skill == null || skill.getTemplate().isNotUsedByAI())
			return false;

		if(skill.getTemplate().getTargetType() == Skill.SkillTargetType.TARGET_SELF && target != actor)
			return false;

		int castRange = skill.getTemplate().getAOECastRange();
		if(castRange <= 200 && distance > 200)
			return false;

		if (skill.getTemplate().getSkillType() == SkillType.TELEPORT_NPC && actor.getPhysicalAttackRange() > distance)
			return false;

		if(actor.isSkillDisabled(skill) || actor.isMuted(skill) || skill.isDisabled())
			return false;

		double mpConsume2 = skill.getTemplate().getMpConsume2();
		if(skill.getTemplate().isMagic())
			mpConsume2 = actor.calcStat(Stats.MP_MAGIC_SKILL_CONSUME, mpConsume2, target, skill);
		else
			mpConsume2 = actor.calcStat(Stats.MP_PHYSICAL_SKILL_CONSUME, mpConsume2, target, skill);
		if(actor.getCurrentMp() < mpConsume2)
			return false;

		if(target.getEffectList().getEffectsCountForSkill(skill.getId()) != 0)
			return false;

		return true;
	}

	protected boolean canUseSkill(SkillEntry sk, Creature target)
	{
		return canUseSkill(sk, target, 0);
	}

	protected SkillEntry[] selectUsableSkills(Creature target, double distance, SkillEntry[] skills)
	{
		if(skills == null || skills.length == 0 || target == null)
			return null;

		SkillEntry[] ret = null;
		int usable = 0;

		for(SkillEntry skill : skills)
			if(canUseSkill(skill, target, distance))
			{
				if(ret == null)
					ret = new SkillEntry[skills.length];
				ret[usable++] = skill;
			}

		if(ret == null || usable == skills.length)
			return ret;

		if(usable == 0)
			return null;

		ret = Arrays.copyOf(ret, usable);
		return ret;
	}

	protected static SkillEntry selectTopSkillByDamage(Creature actor, Creature target, double distance, SkillEntry[] skills)
	{
		if(skills == null || skills.length == 0)
			return null;

		if(skills.length == 1)
			return skills[0];

		RndSelector<SkillEntry> rnd = new RndSelector<SkillEntry>(skills.length);
		double weight;
		for(SkillEntry skill : skills)
		{
			weight = skill.getTemplate().getSimpleDamage(skill, actor, target) * skill.getTemplate().getAOECastRange() / distance;
			if(weight < 1.)
				weight = 1.;
			rnd.add(skill, (int) weight);
		}
		return rnd.select();
	}

	protected static SkillEntry selectTopSkillByDebuff(Creature actor, Creature target, double distance, SkillEntry[] skills) //FIXME
	{
		if(skills == null || skills.length == 0)
			return null;

		if(skills.length == 1)
			return skills[0];

		RndSelector<SkillEntry> rnd = new RndSelector<SkillEntry>(skills.length);
		double weight;
		for(SkillEntry skill : skills)
		{
			if(skill.getTemplate().getSameByStackType(target) != null)
				continue;
			if((weight = 100. * skill.getTemplate().getAOECastRange() / distance) <= 0)
				weight = 1;
			rnd.add(skill, (int) weight);
		}
		return rnd.select();
	}

	protected static SkillEntry selectTopSkillByBuff(Creature target, SkillEntry[] skills)
	{
		if(skills == null || skills.length == 0)
			return null;

		if(skills.length == 1)
			return skills[0];

		RndSelector<SkillEntry> rnd = new RndSelector<SkillEntry>(skills.length);
		double weight;
		for(SkillEntry skill : skills)
		{
			if(skill.getTemplate().getSameByStackType(target) != null)
				continue;
			if((weight = skill.getTemplate().getPower()) <= 0)
				weight = 1;
			rnd.add(skill, (int) weight);
		}
		return rnd.select();
	}

	protected static SkillEntry selectTopSkillByHeal(Creature target, SkillEntry[] skills)
	{
		if(skills == null || skills.length == 0)
			return null;

		double hpReduced = target.getMaxHp() - target.getCurrentHp();
		if(hpReduced < 1)
			return null;

		if(skills.length == 1)
			return skills[0];

		RndSelector<SkillEntry> rnd = new RndSelector<SkillEntry>(skills.length);
		double weight;
		for(SkillEntry skill : skills)
		{
			if((weight = Math.abs(skill.getTemplate().getPower() - hpReduced)) <= 0)
				weight = 1;
			rnd.add(skill, (int) weight);
		}
		return rnd.select();
	}

	protected void addDesiredSkill(Map<SkillEntry, Integer> skillMap, Creature target, double distance, SkillEntry[] skills)
	{
		if(skills == null || skills.length == 0 || target == null)
			return;
		for(SkillEntry sk : skills)
			addDesiredSkill(skillMap, target, distance, sk);
	}

	protected void addDesiredSkill(Map<SkillEntry, Integer> skillMap, Creature target, double distance, SkillEntry skill)
	{
		if(skill == null || target == null || !canUseSkill(skill, target))
			return;
		int weight = (int) -Math.abs(skill.getTemplate().getAOECastRange() - distance);
		if(skill.getTemplate().getAOECastRange() >= distance)
			weight += 1000000;
		else if(skill.getTemplate().isNotTargetAoE() && skill.getTemplate().getTargets(getActor(), target, false).size() == 0)
			return;
		skillMap.put(skill, weight);
	}

	protected void addDesiredHeal(Map<SkillEntry, Integer> skillMap, SkillEntry[] skills)
	{
		if(skills == null || skills.length == 0)
			return;
		NpcInstance actor = getActor();
		double hpReduced = actor.getMaxHp() - actor.getCurrentHp();
		double hpPercent = actor.getCurrentHpPercents();
		if(hpReduced < 1)
			return;
		int weight;
		for(SkillEntry sk : skills)
			if(canUseSkill(sk, actor) && sk.getTemplate().getPower() <= hpReduced)
			{
				weight = (int) sk.getTemplate().getPower();
				if(hpPercent < 50)
					weight += 1000000;
				skillMap.put(sk, weight);
			}
	}

	protected void addDesiredBuff(Map<SkillEntry, Integer> skillMap, SkillEntry[] skills)
	{
		if(skills == null || skills.length == 0)
			return;
		NpcInstance actor = getActor();
		for(SkillEntry sk : skills)
			if(canUseSkill(sk, actor))
				skillMap.put(sk, 1000000);
	}

	protected SkillEntry selectTopSkill(Map<SkillEntry, Integer> skillMap)
	{
		if(skillMap == null || skillMap.isEmpty())
			return null;
		int nWeight, topWeight = Integer.MIN_VALUE;
		for(SkillEntry next : skillMap.keySet())
			if((nWeight = skillMap.get(next)) > topWeight)
				topWeight = nWeight;
		if(topWeight == Integer.MIN_VALUE)
			return null;

		SkillEntry[] skills = new SkillEntry[skillMap.size()];
		nWeight = 0;
		for(Map.Entry<SkillEntry, Integer> e : skillMap.entrySet())
		{
			if(e.getValue() < topWeight)
				continue;
			skills[nWeight++] = e.getKey();
		}
		return skills[Rnd.get(nWeight)];
	}

	protected boolean chooseTaskAndTargets(SkillEntry skillEntry, Creature target, double distance)
	{
		NpcInstance actor = getActor();

		Skill skill = skillEntry == null ? null : skillEntry.getTemplate();
		// Использовать скилл если можно, иначе атаковать
		if(skill != null)
		{
			// Проверка цели, и смена если необходимо
			if(actor.isMovementDisabled() && distance > skill.getAOECastRange() + 60)
			{
				target = null;
				if(skill.isOffensive())
				{
					LazyArrayList<Creature> targets = LazyArrayList.newInstance();
					for(Creature cha : actor.getAggroList().getHateList(MAX_PURSUE_RANGE))
					{
						if(!checkTarget(cha, skill.getAOECastRange() + 60) || !canUseSkill(skillEntry, cha))
							continue;
						targets.add(cha);
					}
					if(!targets.isEmpty())
						target = targets.get(Rnd.get(targets.size()));
					LazyArrayList.recycle(targets);
				}
			}

			if(target == null)
				return false;

			// Добавить новое задание
			if(skill.isOffensive())
				addTaskCast(target, skillEntry);
			else
				addTaskBuff(target, skillEntry);
			return true;
		}

		// Смена цели, если необходимо
		if(actor.isMovementDisabled() && distance > actor.getPhysicalAttackRange() + 40)
		{
			target = null;
			LazyArrayList<Creature> targets = LazyArrayList.newInstance();
			for(Creature cha : actor.getAggroList().getHateList(MAX_PURSUE_RANGE))
			{
				if(!checkTarget(cha, actor.getPhysicalAttackRange() + 40))
					continue;
				targets.add(cha);
			}
			if(!targets.isEmpty())
				target = targets.get(Rnd.get(targets.size()));
			LazyArrayList.recycle(targets);
		}

		if(target == null)
			return false;

		// Добавить новое задание
		addTaskAttack(target);
		return true;
	}

	@Override
	public boolean isActive()
	{
		return _aiTask != null;
	}

	protected void clearTasks()
	{
		_def_think = false;
		_tasks.clear();
	}

	/** переход в режим бега через определенный интервал времени */
	protected void startRunningTask(long interval)
	{
		NpcInstance actor = getActor();
		if(_runningTask == null && !actor.isRunning())
			_runningTask = ThreadPoolManager.getInstance().schedule(new RunningTask(), interval);
	}

	protected boolean isGlobalAggro()
	{
		if(_globalAggro == 0)
			return true;
		if(_globalAggro <= System.currentTimeMillis())
		{
			_globalAggro = 0;
			return true;
		}
		return false;
	}

	public void setGlobalAggro(long value)
	{
		_globalAggro = value;
	}

	@Override
	public NpcInstance getActor()
	{
		return (NpcInstance) super.getActor();
	}

	protected boolean defaultThinkBuff(int rateSelf)
	{
		return defaultThinkBuff(rateSelf, 0);
	}

	/**
	 * Оповестить дружественные цели об атаке.
	 * @param attacker
	 * @param damage
	 */
	protected void notifyFriends(Creature attacker, SkillEntry skill, int damage)
	{
		NpcInstance actor = getActor();
		if(System.currentTimeMillis() - _lastFactionNotifyTime > _minFactionNotifyInterval)
		{
			_lastFactionNotifyTime = System.currentTimeMillis();
			if(actor.isMinion())
			{
				//Оповестить лидера об атаке
				NpcInstance master = actor.getLeader();
				if(master != null)
				{
					if(!master.isDead() && master.isVisible())
						master.getAI().notifyEvent(CtrlEvent.EVT_ATTACKED, attacker, skill, damage);

					//Оповестить минионов лидера об атаке
					MinionList minionList = master.getMinionList();
					if(minionList != null)
						for(NpcInstance minion : minionList.getAliveMinions())
							if(minion != actor)
								minion.getAI().notifyEvent(CtrlEvent.EVT_ATTACKED, attacker, skill, damage);
				}
			}

			//Оповестить своих минионов об атаке
			if (actor.hasMinions())
			{
				MinionList minionList = actor.getMinionList();
				if(minionList.hasAliveMinions())
					for(NpcInstance minion : minionList.getAliveMinions())
						minion.getAI().notifyEvent(CtrlEvent.EVT_ATTACKED, attacker, skill, damage);
			}

			//Оповестить социальных мобов
			for(NpcInstance npc : activeFactionTargets())
				npc.getAI().notifyEvent(CtrlEvent.EVT_CLAN_ATTACKED, actor, attacker, damage);
		}
	}

	protected List<NpcInstance> activeFactionTargets()
	{
		NpcInstance actor = getActor();
		if(actor.getFaction().isNone())
			return Collections.emptyList();
		final int range = actor.getFaction().getRange();
		List<NpcInstance> npcFriends = new LazyArrayList<NpcInstance>();
		for(NpcInstance npc : World.getAroundNpc(actor))
			if(!npc.isDead())
				if(npc.isInRangeZ(actor, range))
					if(npc.isInFaction(actor))
						npcFriends.add(npc);
		return npcFriends;
	}

	protected boolean defaultThinkBuff(int rateSelf, int rateFriends)
	{
		NpcInstance actor = getActor();
		if(actor.isDead())
			return true;

		//TODO сделать более разумный выбор баффа, сначала выбирать подходящие а потом уже рандомно 1 из них
		if(Rnd.chance(rateSelf))
		{
			double actorHp = actor.getCurrentHpPercents();

			SkillEntry[] skills = actorHp < 50 ? selectUsableSkills(actor, 0, _healSkills) : selectUsableSkills(actor, 0, _buffSkills);
			if(skills == null || skills.length == 0)
				return false;

			SkillEntry skill = skills[Rnd.get(skills.length)];
			addTaskBuff(actor, skill);
			return true;
		}

		if(Rnd.chance(rateFriends))
		{
			for(NpcInstance npc : activeFactionTargets())
			{
				double targetHp = npc.getCurrentHpPercents();

				SkillEntry[] skills = targetHp < 50 ? selectUsableSkills(actor, 0, _healSkills) : selectUsableSkills(actor, 0, _buffSkills);
				if(skills == null || skills.length == 0)
					continue;

				SkillEntry skill = skills[Rnd.get(skills.length)];
				addTaskBuff(actor, skill);
				return true;
			}
		}

		return false;
	}

	protected boolean defaultFightTask()
	{
		clearTasks();

		NpcInstance actor = getActor();
		if(actor.isDead() || actor.isAMuted())
			return false;

		Creature target;
		if((target = prepareTarget()) == null)
			return false;

		if (applyUD())
			return true;

		double distance = actor.getDistance(target);
		double targetHp = target.getCurrentHpPercents();
		double actorHp = actor.getCurrentHpPercents();

		SkillEntry[] dam = Rnd.chance(getRateDAM()) ? selectUsableSkills(target, distance, _damSkills) : null;
		SkillEntry[] dot = Rnd.chance(getRateDOT()) ? selectUsableSkills(target, distance, _dotSkills) : null;
		SkillEntry[] debuff = targetHp > 10 ? Rnd.chance(getRateDEBUFF()) ? selectUsableSkills(target, distance, _debuffSkills) : null : null;
		SkillEntry[] stun = Rnd.chance(getRateSTUN()) ? selectUsableSkills(target, distance, _stunSkills) : null;
		SkillEntry[] heal = actorHp < 50 ? Rnd.chance(getRateHEAL()) ? selectUsableSkills(actor, 0, _healSkills) : null : null;
		SkillEntry[] buff = Rnd.chance(getRateBUFF()) ? selectUsableSkills(actor, 0, _buffSkills) : null;

		RndSelector<SkillEntry[]> rnd = new RndSelector<SkillEntry[]>();
		if(!actor.isAMuted())
			rnd.add(null, getRatePHYS());
		rnd.add(dam, getRateDAM());
		rnd.add(dot, getRateDOT());
		rnd.add(debuff, getRateDEBUFF());
		rnd.add(heal, getRateHEAL());
		rnd.add(buff, getRateBUFF());
		rnd.add(stun, getRateSTUN());

		SkillEntry[] selected = rnd.select();
		if(selected != null)
		{
			if(selected == dam || selected == dot)
				return chooseTaskAndTargets(selectTopSkillByDamage(actor, target, distance, selected), target, distance);

			if(selected == debuff || selected == stun)
				return chooseTaskAndTargets(selectTopSkillByDebuff(actor, target, distance, selected), target, distance);

			if(selected == buff)
				return chooseTaskAndTargets(selectTopSkillByBuff(actor, selected), actor, distance);

			if(selected == heal)
				return chooseTaskAndTargets(selectTopSkillByHeal(actor, selected), actor, distance);
		}

		// TODO сделать лечение и баф дружественных целей

		return chooseTaskAndTargets(null, target, distance);
	}

	public int getRatePHYS()
	{
		return 100;
	}

	public int getRateDOT()
	{
		return 0;
	}

	public int getRateDEBUFF()
	{
		return 0;
	}

	public int getRateDAM()
	{
		return 0;
	}

	public int getRateSTUN()
	{
		return 0;
	}

	public int getRateBUFF()
	{
		return 0;
	}

	public int getRateHEAL()
	{
		return 0;
	}

	public boolean getIsMobile()
	{
		return !getActor().getParameter("isImmobilized", false);
	}

	public int getMaxPathfindFails()
	{
		return 3;
	}

	/**
	 * Задержка, перед переключением в активный режим после атаки, если цель не найдена (вне зоны досягаемости, убита, очищен хейт)
	 * @return
	 */
	public int getMaxAttackTimeout()
	{
		return 15000;
	}

	/**
	 * Задержка, перед телепортом к цели, если не удается дойти
	 * @return
	 */
	public int getTeleportTimeout()
	{
		return 10000;
	}
}