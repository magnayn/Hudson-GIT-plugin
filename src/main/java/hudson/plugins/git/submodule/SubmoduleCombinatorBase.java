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

public abstract class SubmoduleCombinatorBase
{
   IGitAPI      git;
   File         workspace;
   TaskListener listener;

   public SubmoduleCombinatorBase(IGitAPI git, TaskListener listener, File workspace)
   {
      this.git = git;
      this.listener = listener;

      this.workspace = workspace;
   }

   /**
    * Given a root (something resolvable to a checkout in the local tree), examine
    * all the submodules that exist within that tree.
    * 
    * Then, look at each submodule in turn, and find what branches exist in those trees,
    * filtering out anything deemed to be 'not interesting'.
    * 
    * Return a map of IndexEntry (refers to the submodule in rootToExamine) 
    *  -> collection of Revisions (which are the tips).
    *  
    * @param rootToExamine - commit to use to look for submodules (e.g. HEAD)
    * @return map of submodules to their interesting revisions.
    * 
    * @throws GitException
    * @throws IOException
    */
   protected Map<IndexEntry, Collection<Revision>> getModuleBranches(String rootToExamine) throws GitException,
         IOException
   {
      GitUtils gitUtils = new GitUtils(listener, git);

      Map<IndexEntry, Collection<Revision>> moduleBranches = new HashMap<IndexEntry, Collection<Revision>>();

      for (IndexEntry submodule : gitUtils.getSubmodules("HEAD"))
      {
         File subdir = new File(workspace, submodule.getFile());
         IGitAPI subGit = new GitAPI(git.getGitExe(), new FilePath(subdir), listener, git.getEnvironment());

         GitUtils gu = new GitUtils(listener, subGit);
         Collection<Revision> items = gu.filterTipBranches(gu.getAllBranchRevisions());

         filterRevisions(submodule.getFile(), items);

         moduleBranches.put(submodule, items);
      }

      for (IndexEntry entry : moduleBranches.keySet())
      {
        listener.getLogger().print("Submodule " + entry.getFile() + " branches");
        for (Revision br : moduleBranches.get(entry))
        {
          listener.getLogger().print(" " + br.toString());

        }
        listener.getLogger().print("\n");
      }
      
      return moduleBranches;
   }

   /**
    * Make all the submodule combinations in the set.
    * 
    * @param settings
    */
   protected void makeCombination(Map<IndexEntry, Revision> settings)
   {
     // Assume we are checked out    
     String commit = "Hudson generated combination of:\n";
     
     for (IndexEntry submodule : settings.keySet())
     {
       Revision branch = settings.get(submodule);
       commit += "  " + submodule.getFile() + " " + branch.toString() + "\n";
     }
     
     listener.getLogger().print(commit);
     
     for (IndexEntry submodule : settings.keySet())
     {
       Revision branch = settings.get(submodule);
       File subdir = new File(workspace, submodule.getFile());
       IGitAPI subGit = new GitAPI(git.getGitExe(), new FilePath(subdir), listener, git.getEnvironment());
       
       subGit.checkout(branch.getSha1().name());
       git.add(submodule.getFile());
       
     }
     
     try
     {
       File f = File.createTempFile("gitcommit", ".txt");
       FileOutputStream fos = null;
       try
       {
         fos = new FileOutputStream(f);
         fos.write(commit.getBytes());
       }
       finally
       {
         fos.close();
       }
       git.commit(f);
       f.delete();
     }
     catch (IOException e)
     {
       // TODO Auto-generated catch block
       e.printStackTrace();
     }

   }
   
   /** 
    * Given a set of pre-existing submodule combinations, remove any that already
    * exist from a set of candidate combinations.
    * 
    * @param combinations
    * @param alreadyExistingCombinations
    */
   protected void removeCombinationsThatAlreadyExist(List<Map<IndexEntry, Revision>> combinations,
         Map<ObjectId, List<IndexEntry>> alreadyExistingCombinations)
   {    
      for (List<IndexEntry> entries : alreadyExistingCombinations.values())
      {
        for (Iterator<Map<IndexEntry, Revision>> it = combinations.iterator(); it.hasNext();)
        {
          Map<IndexEntry, Revision> item = it.next();
          if (matches(item, entries))
          {
            it.remove();
            break;
          }

        }
      }      
   }
   
   /**
    * Compute how many submodules are different.
    * @param item
    * @param entries
    * @return
    */
   protected int difference(Map<IndexEntry, Revision> item, List<IndexEntry> entries)
   {
      int difference = 0;
      if (entries.size() != item.keySet().size()) return -1;

      for (IndexEntry entry : entries)
      {
         Revision b = null;
         for (IndexEntry e : item.keySet())
         {
            if (e.getFile().equals(entry.getFile())) b = item.get(e);
         }

         if (b == null) return -1;

         if (!entry.getObject().equals(b.getSha1())) difference++;

      }
      return difference;
   }

   /**
    * Does the item passed in have the same checkouts of submodules?
    * @param item
    * @param entries
    * @return
    */
   protected boolean matches(Map<IndexEntry, Revision> item, List<IndexEntry> entries)
   {
     return (difference(item, entries) == 0);
   }
   
   /**
    * Look at the entire tree, and find all the submodule combinations
    * that exist in it.
    * @return
    */
    protected Map<ObjectId, List<IndexEntry>> getExistingCombinations()
    {
       GitUtils gitUtils = new GitUtils(listener, git);

       
       // Create a map which is SHA1 -> Submodule IDs that were present
       Map<ObjectId, List<IndexEntry>> entriesMap = new HashMap<ObjectId, List<IndexEntry>>();
       
       // Knock out already-defined configurations
       for (ObjectId sha1 : git.revListAll())
       {
         // What's the submodule configuration
         List<IndexEntry> entries = gitUtils.getSubmodules(sha1.name());
         entriesMap.put(sha1, entries);

       }
       
       return entriesMap;
    }
   
   /**
    * Filter the provided collection of revisions, removing any that are not interesting to
    * the combinator.
    * @param name - name of the submodule
    * @param items - collection of (tip) revisions
    * @return items
    */
   protected abstract Collection<Revision> filterRevisions(String name, Collection<Revision> items);

   /**
    * Given a set of submodules and revisions, create a list of all the possible
    * combinations that could exist.
    * 
    * e.g.
    * given modA, branchA, branchB
    *       modB, branchC, branchD
    *       
    * supplies
    *      (modA->branchA),(modB->branchC),
    *      (modA->branchA),(modB->branchD),
    *      (modA->branchB),(modB->branchC),
    *      (modA->branchB),(modB->branchD),
    *      
    * 
    * @param moduleBranches
    * @return
    */
   protected List<Map<IndexEntry, Revision>> createCombinations(Map<IndexEntry, Collection<Revision>> moduleBranches)
   {
     
     if (moduleBranches.keySet().size() == 0) return new ArrayList<Map<IndexEntry, Revision>>();

     // Get an entry:
     List<Map<IndexEntry, Revision>> thisLevel = new ArrayList<Map<IndexEntry, Revision>>();

     IndexEntry e = moduleBranches.keySet().iterator().next();

     for (Revision b : moduleBranches.remove(e))
     {
       Map<IndexEntry, Revision> result = new HashMap<IndexEntry, Revision>();

       result.put(e, b);
       thisLevel.add(result);
     }

     List<Map<IndexEntry, Revision>> children = createCombinations(moduleBranches);
     if (children.size() == 0) return thisLevel;
     
     // Merge the two together
     List<Map<IndexEntry, Revision>> result = new ArrayList<Map<IndexEntry, Revision>>();

     for (Map<IndexEntry, Revision> thisLevelEntry : thisLevel)
     {
       
       for (Map<IndexEntry, Revision> childLevelEntry : children)
       {
         HashMap<IndexEntry, Revision> r = new HashMap<IndexEntry, Revision>();
         r.putAll(thisLevelEntry);
         r.putAll(childLevelEntry);
         result.add(r);
       }
       
     }

     return result;
   }
}
