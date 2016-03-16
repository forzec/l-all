package ai;

import org.mmocore.commons.util.Rnd;
import org.mmocore.gameserver.ai.DefaultAI;
import org.mmocore.gameserver.model.Creature;
import org.mmocore.gameserver.model.instances.NpcInstance;
import org.mmocore.gameserver.network.l2.components.NpcString;
import org.mmocore.gameserver.scripts.Functions;

public class BlacksmithMammon extends DefaultAI
{
	private long _chatVar = 0;
	private static final long chatDelay = 30 * 60 * 1000L;

	/** Messages of NPCs **/
	private static final NpcString[] mamonText = {
			NpcString.RULERS_OF_THE_SEAL_I_BRING_YOU_WONDROUS_GIFTS,
			NpcString.RULERS_OF_THE_SEAL_I_HAVE_SOME_EXCELLENT_WEAPONS_TO_SHOW_YOU,
			NpcString.IVE_BEEN_SO_BUSY_LATELY_IN_ADDITION_TO_PLANNING_MY_TRIP };

	public BlacksmithMammon(NpcInstance actor)
	{
		super(actor);
	}

	@Override
	protected boolean thinkActive()
	{
		NpcInstance actor = getActor();
		if(actor.isDead())
			return true;

		if(_chatVar + chatDelay < System.currentTimeMillis())
		{
			_chatVar = System.currentTimeMillis();
			Functions.npcShout(actor, mamonText[Rnd.get(mamonText.length)]);
		}

		return false;
	}

	@Override
	public boolean isGlobalAI()
	{
		return true;
	}
}