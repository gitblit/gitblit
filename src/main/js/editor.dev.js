attachDocumentEditor = function (editorElement, commitDialogElement)
{
	var edit = require("./prosemirror/dist/edit")
	require("./prosemirror/dist/inputrules/autoinput")
	require("./prosemirror/dist/menu/menubar")
	require("./prosemirror/dist/markdown")
	var _menu = require("./prosemirror/dist/menu/menu")
	
	var link = window.location.href;
	var extIdx = link.lastIndexOf('.');
	var ext = extIdx > 0 && (extIdx > link.length - 8) ? link.substring(extIdx+1) : "";
	//FIXME It work, but is not a good idea.
	var isMd=(ext=='md'||ext=='mkd'||ext=='markdown'||ext=='MD'||ext=='MKD');
	
	var content = document.querySelector('#editor');
	content.style.display = "none";
	
	var gitblitCommands = new _menu.MenuCommandGroup("gitblitCommands");
	var viewCommands = new _menu.MenuCommandGroup("viewCommands");
	var textCommands = new _menu.MenuCommandGroup("textCommands");
	var insertCommands = new _menu.MenuCommandGroup("insertCommands");
	
	var menuItems = isMd ? 
	    [gitblitCommands, viewCommands, textCommands, _menu.inlineGroup, _menu.blockGroup, _menu.historyGroup, insertCommands]
	    : [gitblitCommands, viewCommands, _menu.historyGroup];
	
	const updateCmd = Object.create(null);
	
	updateCmd["GitblitCommit"] = {
		label: "GitblitCommit",
		run: function() {
			commitDialogElement.modal({show:true});
			editorElement.value = isMd ? pm.getContent('markdown') : pm.getContent('text');
		},
		menu: {
			group: "gitblitCommands", rank: 10,
			display: {
				render: function(cmd, pm) { return renderFontAwesomeIcon(cmd, pm, "fa-save"); }
			}
		  }
	};
	
	updateCmd["FullScreen"] = {
	label: "Toggle Fullscreen",
	derive: "toggle",
	run: function(pm) {
		//Maintain the scroll context
		var initialScroll = window.scrollY;
		var navs = [document.querySelector("div.repositorynavbar"), document.querySelector("div.navbar"), document.querySelector("div.docnav")];
		var offset = navs.reduce(function(p, c) { return p + c.offsetHeight; }, 0);
		navs.forEach(function(e) { e.classList.toggle("forceHide"); });
		
		if (!toggleFullScreen(document.documentElement)) {
			offset = 60;
		} else {
			offset -= 60;
		}
		
		pm.signal("commandsChanged");
		
		//Browsers don't seem to accept a scrollTo straight after a full screen
		setTimeout(function(){window.scrollTo(0, Math.max(0,initialScroll - offset));}, 100);
		
	},
	 menu: {
		group: "viewCommands", rank: 11,
		display: {
			render: function(cmd, pm) { return renderFontAwesomeIcon(cmd, pm, "fa-arrows-alt"); }
		}
	  },
	  active: function active(pm) { return getFullScreenElement() ? true : false; }
	};
	
	updateCmd["heading1"] = {
		derive: "toggle",
		run: function(pm) {
			var selection = pm.selection;
			var from = selection.from;
			var to = selection.to;
			var attr = {name:"make", level:"1"};
			
			var node = pm.doc.resolve(from).parent;
			if (node && node.hasMarkup(pm.schema.nodes.heading, attr)) {
				return pm.tr.setBlockType(from, to, pm.schema.defaultTextblockType(), {}).apply(pm.apply.scroll);
			} else {
				return pm.tr.setBlockType(from, to, pm.schema.nodes.heading, attr).apply(pm.apply.scroll);
			}
			
		},
		active: function active(pm) {
			var node = pm.doc.resolve(pm.selection.from).parent;
			if (node && node.hasMarkup(pm.schema.nodes.heading, {name:"make", level:"1"})) {
				return true;
			}
			return false;
		},
		menu: {
			group: "textCommands", rank: 1,
			display: {
			  render: function(cmd, pm) { return renderFontAwesomeIcon(cmd, pm, "fa-header fa-header-x fa-header-1"); }
			},
		  },
		  select: function(){return true;}
	};
	
	updateCmd["heading2"] = {
		derive: "toggle",
		run: function(pm) {
			var selection = pm.selection;
			var from = selection.from;
			var to = selection.to;
			var attr = {name:"make", level:"2"};
			
			var node = pm.doc.resolve(from).parent;
			if (node && node.hasMarkup(pm.schema.nodes.heading, attr)) {
				return pm.tr.setBlockType(from, to, pm.schema.defaultTextblockType(), {}).apply(pm.apply.scroll);
			} else {
				return pm.tr.setBlockType(from, to, pm.schema.nodes.heading, attr).apply(pm.apply.scroll);
			}
			
		},
		active: function active(pm) {
			var node = pm.doc.resolve(pm.selection.from).parent;
			if (node && node.hasMarkup(pm.schema.nodes.heading, {name:"make", level:"2"})) {
				return true;
			}
			return false;
		},
		menu: {
			group: "textCommands", rank: 2,
			display: {
			  render: function(cmd, pm) { return renderFontAwesomeIcon(cmd, pm, "fa-header fa-header-x fa-header-2"); }
			},
		  },
		  select: function(){return true;}
	};
	
	updateCmd["heading3"] = {
		derive: "toggle",
		run: function(pm) {
			var selection = pm.selection;
			var from = selection.from;
			var to = selection.to;
			var attr = {name:"make", level:"3"};
			
			var node = pm.doc.resolve(from).parent;
			if (node && node.hasMarkup(pm.schema.nodes.heading, attr)) {
				return pm.tr.setBlockType(from, to, pm.schema.defaultTextblockType(), {}).apply(pm.apply.scroll);
			} else {
				return pm.tr.setBlockType(from, to, pm.schema.nodes.heading, attr).apply(pm.apply.scroll);
			}
			
		},
		active: function active(pm) {
			var node = pm.doc.resolve(pm.selection.from).parent;
			if (node && node.hasMarkup(pm.schema.nodes.heading, {name:"make", level:"3"})) {
				return true;
			}
			return false;
		},
		menu: {
			group: "textCommands", rank: 3,
			display: {
			  render: function(cmd, pm) { return renderFontAwesomeIcon(cmd, pm, "fa-header fa-header-x fa-header-3"); }
			},
		  },
		  select: function(){return true;}
	};
		
	updateCmd["strong:toggle"] = {
	menu: {
		group: "textCommands", rank: 4,
		display: {
			render: function(cmd, pm) { return renderFontAwesomeIcon(cmd, pm, "fa-bold"); }
		}
	  },
	  select: function(){return true;}
	};
	
	updateCmd["em:toggle"] = {
	menu: {
		group: "textCommands", rank: 5,
		display: {
			render: function(cmd, pm) { return renderFontAwesomeIcon(cmd, pm, "fa-italic"); }
		}
	  },
	  select: function(){return true;}
	};
	
	updateCmd["code:toggle"] = {
	menu: {
		group: "textCommands", rank: 6,
		display: {
			render: function(cmd, pm) { return renderFontAwesomeIcon(cmd, pm, "fa-code"); }
		}
	  },
	  select: function(){return true;}
	};
	
	updateCmd["image:insert"] = {
	menu: {
		group: "insertCommands", rank: 1,
		display: {
			render: function(cmd, pm) { return renderFontAwesomeIcon(cmd, pm, "fa-picture-o"); }
		}
	  }
	};
	
	updateCmd["selectParentNode"] = {
	menu: {
		group: "insertCommands", rank: 10,
		display: {
			render: function(cmd, pm) { return renderFontAwesomeIcon(cmd, pm, "fa-arrow-circle-o-left"); }
		}
	  }
	};
	
	var pm = window.pm = new edit.ProseMirror({
	  place: document.querySelector('#visualEditor'),
	  autoInput: true,
	  doc: content.value,
	  menuBar: { float:true, content: menuItems},
	  commands: edit.CommandSet.default.update(updateCmd),
	  docFormat: isMd ? "markdown" : "text"
	});
	

	var scrollStart = document.querySelector(".ProseMirror").offsetTop;
	
	
	var ticking = false;
	window.addEventListener("scroll", function() {
		var scrollPosition = window.scrollY;
		if (!ticking) {
			window.requestAnimationFrame(function() {
				if (!getFullScreenElement() && (scrollPosition > scrollStart)) {
					document.querySelector(".ProseMirror-menubar").classList.add("scrolling");
				} else {
					document.querySelector(".ProseMirror-menubar").classList.remove("scrolling");
				}
				ticking = false;
			});
		}
		ticking = true;
	});
}

function renderFontAwesomeIcon(cmd, pm, classNames) {
	var node = document.createElement("div");
	node.className = "ProseMirror-icon";
	var icon = document.createElement("i");
	icon.setAttribute("class", "fa fa-fw " + classNames);
	
	var active = cmd.active(pm);
	
	if (active || cmd.spec.invert) node.classList.add("ProseMirror-menu-active");
	node.appendChild(icon);
	return node;
}



function getFullScreenElement() {
	return document.fullscreenElement || document.mozFullScreenElement || document.webkitFullscreenElement || document.msFullscreenElement;
}

function toggleFullScreen(e) {
  if (getFullScreenElement()) {
    if      (document.exitFullscreen)       { document.exitFullscreen(); }
	else if (document.msExitFullscreen)     { document.msExitFullscreen(); }
	else if (document.mozCancelFullScreen)  { document.mozCancelFullScreen(); }
	else if (document.webkitExitFullscreen) { document.webkitExitFullscreen(); }
	return true;
  } else {
	if      (e.requestFullscreen)       { e.requestFullscreen(); }
	else if (e.msRequestFullscreen)     { e.msRequestFullscreen(); }
	else if (e.mozRequestFullScreen)    { e.mozRequestFullScreen(); }
	else if (e.webkitRequestFullscreen) { e.webkitRequestFullscreen(Element.ALLOW_KEYBOARD_INPUT); }
  }
  return false;
}

commitChanges = function() {
	document.querySelector('form#documentEditor').submit();
}
	
