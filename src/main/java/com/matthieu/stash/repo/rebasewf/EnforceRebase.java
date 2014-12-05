package com.matthieu.stash.repo.rebasewf;

import com.atlassian.stash.hook.HookResponse;
import com.atlassian.stash.hook.repository.*;
import com.atlassian.stash.repository.*;
import com.atlassian.stash.commit.CommitService;
import com.atlassian.stash.content.ChangesetsBetweenRequest;
import com.atlassian.stash.content.AbstractChangesetCallback;
import com.atlassian.stash.content.Changeset;
import com.atlassian.stash.content.MinimalChangeset;

import org.springframework.util.PathMatcher;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.io.IOException;
import java.io.InputStream;
import java.lang.String;

public class EnforceRebase implements PreReceiveRepositoryHook {
  private final CommitService commitService;
  final static PathMatcher ref_matcher = new AntPathMatcher();

  public EnforceRebase(CommitService commitService) {
    this.commitService = commitService;
  }

  private class changesetReceiver extends AbstractChangesetCallback {
    String parent;
    boolean inBranch;
    String branch[] = new String[2];
    boolean branch_with_children[] = new boolean[2];

    String error;
    boolean success;
    boolean strict;

    public changesetReceiver(String latest, boolean strict) {
      this.parent = latest;
      this.inBranch = false;
      this.success = true; // until proven otherwise
      this.error = "";
      this.strict = strict;
    }

    public String getError() {
      if (this.error.equals("") && this.inBranch) {
        return "First commit is a merge";
      } else {
        return this.error;
      }
    }

    public boolean getResult() {
      return (this.success && !this.inBranch);
    }

    @Override
    public boolean onChangeset(Changeset change) {
      //ids.add(change.getDisplayId() + " / " + change.getParents().size());
      if (change.getParents().size() == 0) {
        // looks like we're done
        return true;
      }
      Iterator<MinimalChangeset> parents = change.getParents().iterator();

      if (change.getParents().size() == 1) {
        String onlyParent = parents.next().getId();

        if (!this.inBranch) {
          // we are not in a branch

          if (!change.getId().equals(this.parent)) {
            // oops, we're not following the history linearly, something went wrong, just give up the check
            this.error = "I got confused between "+this.parent+" and "+change.getId();
            this.success = false;
            return false;
          }

          // move to the next item
          this.parent = onlyParent;
          return true;
        } else {
          // we are in a branch, let's find which side is moving
          int branch_index;
          if (change.getId().equals(this.branch[0])) {
            branch_index = 0;
          } else if (change.getId().equals(this.branch[1])) {
            branch_index = 1;
          } else {
            // got confused again
            this.error = "I got confused between "+this.branch[0]+","+this.branch[1]+" and "+change.getId();
            this.success = false;
            return false;
          }
          
          // check we are not violating anything
          if (branch_with_children[1-branch_index]) {
            this.error = "Commits in different branches, "+change.getId()+" and "+this.branch[1-branch_index];
            this.success = false;
            return false;
          }

          // see if we are merging anything
          if (onlyParent.equals(branch[1-branch_index])) {
            this.parent = onlyParent;
            this.inBranch = false;
            return true;
          }

          // nothing special, let's go down this branch
          this.branch_with_children[branch_index] = true;
          this.branch[branch_index] = onlyParent;
          return true;
        }
      }

      if (change.getParents().size() == 2) {
        if (this.strict) {
          this.error = "no merge allowed in this branch";
          this.success = false;
          return false;
        }

        if (this.inBranch) {
          this.error = "commit id "+ change.getId() +" is not allowed. Please rebase";
          this.success = false;
          return false;
        }
        this.branch_with_children[0] = false;
        this.branch_with_children[1] = false;
        this.branch[0] = parents.next().getId();
        this.branch[1] = parents.next().getId();
        this.inBranch = true;

        return true;
      }

      this.error = "Too many parents ?!? At commit " + change.getId();
      this.success = false;
      return false;
    }
  }

  private ArrayList<String> getRefs(RepositoryHookContext context, boolean strict) {
    String[] refs = context.getSettings().getString(strict?"strict_references":"references").split(" ");
    ArrayList<String> result = new ArrayList<String>();
    for (String ref : refs) {
      if (ref.equals("")) {
        continue;
      }
      if ((!ref.startsWith("**")) && (!ref.startsWith("refs/"))) {
        ref = "refs/"+ref;
      }
      if (ref.endsWith("/") || ref.endsWith("\\")) {
        ref = ref + "**";
      }
      result.add(ref);
    }
    return result;
  }

  @Override
  public boolean onReceive(RepositoryHookContext context, Collection<RefChange> refChanges, final HookResponse hookResponse) {
    final Repository repo = context.getRepository();
    ArrayList<String> strict_ref_patterns = getRefs(context, true);
    ArrayList<String> non_strict_ref_patterns  = getRefs(context, false);

    boolean strict=false;

    //hookResponse.out().println("Number of changes: "+refChanges.size());
    for (RefChange r: refChanges) {
      //hookResponse.out().println("checking ref "+r.getRefId());
      //hookResponse.out().println("Going from :"+r.getFromHash()+" to "+r.getToHash());

      boolean found = false;
      for (String pattern : strict_ref_patterns) {
        if (ref_matcher.match(pattern, r.getRefId())) {
          strict = true;
          found = true;
          //hookResponse.out().println("Will check " + r.getRefId() + " strictly");
          break;
        }
      }
      if (!found) {
        for (String pattern : non_strict_ref_patterns) {
          if (ref_matcher.match(pattern, r.getRefId())) {
            strict = false;
            found = true;
            //hookResponse.out().println("Will check " + r.getRefId() + " non strictly");
            break;
          }
        }
      }
      if (!found) {
        //hookResponse.out().println("Will not check " + r.getRefId());
        // nothing to check, move on
        continue;
      }

      ChangesetsBetweenRequest request = new ChangesetsBetweenRequest.Builder(repo).include(r.getToHash()).exclude(r.getFromHash()).build();

      changesetReceiver callback = new changesetReceiver(r.getToHash(), strict);
      commitService.streamChangesetsBetween(request, callback);
      //hookResponse.out().println("The check returned : "+(callback.getResult() ? "success":"failure")+" with error "+callback.getError());
      if (!callback.getResult()) {
        hookResponse.err().println("Pushing of reference "+r.getRefId()+" failed");
        hookResponse.err().println(callback.getError());
        return false;
      }
    }

    return true;
  }
}
