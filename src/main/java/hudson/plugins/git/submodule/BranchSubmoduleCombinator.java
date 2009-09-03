package hudson.plugins.git.submodule;

import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.plugins.git.IGitAPI;
import hudson.plugins.git.IndexEntry;
import hudson.plugins.git.Revision;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.spearce.jgit.lib.ObjectId;

/**
 * A simple submodule combinator. If we pass origin/x to createSubmoduleCombinations, then only consider origin/x on
 * each submodule.
 * 
 * @author magnayn
 */
public class BranchSubmoduleCombinator extends SubmoduleCombinatorBase implements ICombinator
{
   long             tid = new Date().getTime();
   long             idx = 1;

   protected String root;

   public BranchSubmoduleCombinator(IGitAPI git, TaskListener listener, File workspace, String rootToExamine)
   {
      super(git, listener, workspace);
      root = rootToExamine;
   }

   /**
    * Create submodule combinations, and commit them to the repository.
    * 
    * @throws GitException
    * @throws IOException
    */
   public void createSubmoduleCombinations() throws GitException, IOException
   {
      
      Map<IndexEntry, Collection<Revision>> moduleBranches = getModuleBranches(root);

      // Make all the possible combinations
      List<Map<IndexEntry, Revision>> combinations = createCombinations(moduleBranches);

      listener.getLogger().println("There are " + combinations.size() + " submodule/revision combinations possible");

      Map<ObjectId, List<IndexEntry>> areadyExistingCombinations = getExistingCombinations();

      removeCombinationsThatAlreadyExist(combinations, areadyExistingCombinations);

      listener.getLogger().println("There are " + combinations.size() + " configurations that could be generated.");

      ObjectId headSha1 = git.revParse(root);

      // Make up the combination(s)
      for (Map<IndexEntry, Revision> combination : combinations)
      {
         // By default, use the head sha1
         ObjectId sha1 = headSha1;
         git.checkout(sha1.name());
         makeCombination(combination);
      }

   }

   protected Collection<Revision> filterRevisions(String name, Collection<Revision> items)
   {
      for (Iterator<Revision> it = items.iterator(); it.hasNext();)
      {
         Revision r = it.next();
         boolean pass = false;
         for (Branch b : r.getBranches())
         {
            // We only care about branches with the same name as the superproject..
            if (b.getName().equals(this.root)) pass = true;
         }

         if (!pass) it.remove();
      }

      return items;
   }

   protected void makeCombination(Map<IndexEntry, Revision> settings)
   {
      // Assume we are checked out
      String name = "combine-" + tid + "-" + (idx++);
      git.branch(name);
      git.checkout(name);

      super.makeCombination(settings);

   }

}
