package hudson.plugins.git.submodule;

import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.plugins.git.GitAPI;
import hudson.plugins.git.GitException;
import hudson.plugins.git.IGitAPI;
import hudson.plugins.git.IndexEntry;
import hudson.plugins.git.Revision;
import hudson.plugins.git.util.GitUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.spearce.jgit.lib.ObjectId;

/**
 * A common usecase for git submodules is to have child submodules, and a parent 'configuration' project that ties the
 * correct versions together. It is useful to be able to speculatively compile all combinations of submodules, so that
 * you can _know_ if a particular combination is no longer compatible.
 * 
 * @author nigelmagnay
 */
public class SubmoduleCombinator extends SubmoduleCombinatorBase implements ICombinator
{
   long                        tid = new Date().getTime();
   long                        idx = 1;
   String rootToExamine            = "HEAD";
   Collection<SubmoduleConfig> submoduleConfig;

   public SubmoduleCombinator(IGitAPI git, TaskListener listener, File workspace, Collection<SubmoduleConfig> cfg)
   {
      super(git, listener, workspace);
      this.submoduleConfig = cfg;
   }

   /**
    * Create submodule combinations, and commit them to the repository.
    * 
    * @throws GitException
    * @throws IOException
    */
   public void createSubmoduleCombinations() throws GitException, IOException
   {
      GitUtils gitUtils = new GitUtils(listener, git);

      Map<IndexEntry, Collection<Revision>> moduleBranches = getModuleBranches(rootToExamine);

      // Make all the possible combinations
      List<Map<IndexEntry, Revision>> combinations = createCombinations(moduleBranches);

      listener.getLogger().println("There are " + combinations.size() + " submodule/revision combinations possible");

      Map<ObjectId, List<IndexEntry>> areadyExistingCombinations = getExistingCombinations();

      removeCombinationsThatAlreadyExist(combinations, areadyExistingCombinations);

      listener.getLogger().println("There are " + combinations.size() + " configurations that could be generated.");

      ObjectId headSha1 = git.revParse(rootToExamine);

      // Make up the combinations, branch off the 'closest' commit
      for (Map<IndexEntry, Revision> combination : combinations)
      {
         // By default, use the head sha1
         ObjectId sha1 = headSha1;
         int min = Integer.MAX_VALUE;

         // But let's see if we can find the most appropriate place to create the branch
         for (ObjectId sha : areadyExistingCombinations.keySet())
         {
            List<IndexEntry> entries = areadyExistingCombinations.get(sha);
            int value = difference(combination, entries);
            if (value > 0 && value < min)
            {
               min = value;
               sha1 = sha;
            }

            if (min == 1) break; // look no further
         }

         git.checkout(sha1.name());
         makeCombination(combination);
      }

   }

   protected Collection<Revision> filterRevisions(String name, Collection<Revision> items)
   {
      SubmoduleConfig config = getSubmoduleConfig(name);
      if (config == null) return items;

      for (Iterator<Revision> it = items.iterator(); it.hasNext();)
      {
         Revision r = it.next();
         if (!config.revisionMatchesInterest(r)) it.remove();
      }

      return items;
   }

   private SubmoduleConfig getSubmoduleConfig(String name)
   {
      for (SubmoduleConfig config : this.submoduleConfig)
      {
         if (config.getSubmoduleName().equals(name)) return config;
      }
      return null;
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
